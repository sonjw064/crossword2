// 로그인 상태(게스트 포함)와 토큰. 토큰은 localStorage에 둔다 — innerHTML을 쓰지 않고 CSP로 XSS를 막는 전제.
import { rawRequest, ApiError } from './http.js';

const KEY = 'crossword.auth';
const REFRESH_MARGIN_MS = 60 * 1000;

let state = load();
/** 명시적 로그인/가입/로그아웃마다 증가한다. 진행 중이던 refresh가 늦게 끝나도 오래된 응답이 상태를 덮어쓰지 못하게 한다. */
let generation = 0;
let refreshing = null; // { generation, promise }
const listeners = new Set();

function load() {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function setState(next) {
  state = next;
  try {
    if (next) localStorage.setItem(KEY, JSON.stringify(next));
    else localStorage.removeItem(KEY);
  } catch {
    // 저장소를 쓸 수 없으면 이번 방문에서만 유지된다
  }
  listeners.forEach((fn) => fn(currentUser()));
}

/** 사용자가 직접 한 변경(로그인/가입/로그아웃): 진행 중인 refresh 결과를 무효화한다. */
function replaceState(next) {
  generation++;
  setState(next);
}

function fromResponse(res) {
  return {
    accessToken: res.accessToken,
    refreshToken: res.refreshToken,
    expiresAt: Date.now() + res.expiresIn * 1000,
    user: { type: res.ownerType, id: res.ownerId, nickname: res.nickname, role: res.role ?? null },
  };
}

/** 서버에서 폐기하지 못해도(네트워크 오류 등) 이 기기에서는 이미 정리된 상태이므로 오류는 무시한다. */
function revokeQuietly(refreshToken) {
  return rawRequest('POST', '/api/auth/logout', { body: { refreshToken } }).catch(() => {});
}

/** @returns {{type:'GUEST'|'MEMBER', id:string, nickname:string, role:string|null}|null} */
export function currentUser() {
  return state ? state.user : null;
}

export function onChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export async function startGuest(nickname) {
  replaceState(fromResponse(await rawRequest('POST', '/api/auth/guest', { body: { nickname } })));
}

export async function signup(email, password, nickname) {
  replaceState(fromResponse(await rawRequest('POST', '/api/auth/signup', { body: { email, password, nickname } })));
}

export async function login(email, password) {
  replaceState(fromResponse(await rawRequest('POST', '/api/auth/login', { body: { email, password } })));
}

export async function logout() {
  const token = state?.refreshToken;
  replaceState(null);
  if (token) await revokeQuietly(token);
}

/**
 * refresh 토큰으로 새 토큰을 받는다. 같은 상태에서 동시에 여러 번 불리면 한 번만 보낸다(교체형 토큰이라 중복 호출이 위험).
 * 응답이 도착했을 때 그 사이 로그아웃/다른 계정 로그인이 있었다면 결과를 버리고, 새로 발급된 토큰은 폐기한다.
 */
export function refreshNow() {
  if (!state) return Promise.resolve(null);
  if (refreshing && refreshing.generation === generation) return refreshing.promise;

  const startedAt = generation;
  const used = state.refreshToken;
  const promise = rawRequest('POST', '/api/auth/refresh', { body: { refreshToken: used } })
    .then((res) => {
      if (startedAt !== generation) {
        revokeQuietly(res.refreshToken); // 이미 다른 상태가 됨: 방금 발급된 토큰이 남지 않게 한다
        return null;
      }
      const next = fromResponse(res);
      setState(next);
      return next.accessToken;
    })
    .catch((e) => {
      if (startedAt !== generation) return null;
      if (e instanceof ApiError && e.status === 401) {
        // 다른 탭이 먼저 갱신했다면 저장소에 더 새로운 토큰이 있다: 로그아웃하지 말고 그것을 이어받는다
        const stored = load();
        if (stored && stored.refreshToken !== used) {
          setState(stored);
          return stored.accessToken;
        }
        setState(null); // 만료/폐기: 다시 로그인해야 한다
      }
      return null;
    })
    .finally(() => {
      if (refreshing && refreshing.promise === promise) refreshing = null;
    });
  refreshing = { generation: startedAt, promise };
  return promise;
}

/** 유효한 access 토큰. 곧 만료되면 먼저 갱신한다. 로그인 상태가 아니면 null. */
export async function getAccessToken() {
  if (!state) return null;
  if (state.expiresAt - Date.now() > REFRESH_MARGIN_MS) return state.accessToken;
  return (await refreshNow()) ?? (state ? state.accessToken : null);
}
