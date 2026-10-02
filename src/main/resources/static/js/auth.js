// 로그인 상태(게스트 포함)와 토큰. 토큰은 localStorage에 둔다 — innerHTML을 쓰지 않고 CSP로 XSS를 막는 전제.
import { rawRequest, ApiError } from './http.js';

const KEY = 'crossword.auth';
const REFRESH_MARGIN_MS = 60 * 1000;

let state = load();
let refreshing = null;
const listeners = new Set();

function load() {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function save(next) {
  state = next;
  try {
    if (next) localStorage.setItem(KEY, JSON.stringify(next));
    else localStorage.removeItem(KEY);
  } catch {
    // 저장소를 쓸 수 없으면 이번 방문에서만 유지된다
  }
  listeners.forEach((fn) => fn(currentUser()));
}

function fromResponse(res) {
  return {
    accessToken: res.accessToken,
    refreshToken: res.refreshToken,
    expiresAt: Date.now() + res.expiresIn * 1000,
    user: { type: res.ownerType, id: res.ownerId, nickname: res.nickname, role: res.role ?? null },
  };
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
  save(fromResponse(await rawRequest('POST', '/api/auth/guest', { body: { nickname } })));
}

export async function signup(email, password, nickname) {
  save(fromResponse(await rawRequest('POST', '/api/auth/signup', { body: { email, password, nickname } })));
}

export async function login(email, password) {
  save(fromResponse(await rawRequest('POST', '/api/auth/login', { body: { email, password } })));
}

export async function logout() {
  const token = state?.refreshToken;
  save(null);
  if (token) {
    try {
      await rawRequest('POST', '/api/auth/logout', { body: { refreshToken: token } });
    } catch {
      // 서버에서 폐기하지 못해도 이 기기에서는 로그아웃된 상태다
    }
  }
}

/** refresh 토큰으로 새 토큰을 받는다. 동시에 여러 번 불리면 한 번만 보낸다(교체형 토큰이라 중복 호출이 위험). */
export function refreshNow() {
  if (!state) return Promise.resolve(null);
  if (!refreshing) {
    const used = state.refreshToken;
    refreshing = rawRequest('POST', '/api/auth/refresh', { body: { refreshToken: used } })
      .then((res) => {
        save(fromResponse(res));
        return state.accessToken;
      })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 401) save(null); // 만료/폐기: 다시 로그인해야 한다
        return null;
      })
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

/** 유효한 access 토큰. 곧 만료되면 먼저 갱신한다. 로그인 상태가 아니면 null. */
export async function getAccessToken() {
  if (!state) return null;
  if (state.expiresAt - Date.now() > REFRESH_MARGIN_MS) return state.accessToken;
  return (await refreshNow()) ?? (state ? state.accessToken : null);
}
