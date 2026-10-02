import { rawRequest, ApiError } from './http.js';
import * as auth from './auth.js';

export { ApiError };

/** 로그인(게스트 포함) 상태면 토큰을 붙이고, 401이면 한 번 갱신해 다시 시도한다. */
async function request(method, url, options = {}) {
  const token = await auth.getAccessToken();
  try {
    return await rawRequest(method, url, { ...options, token });
  } catch (e) {
    if (e instanceof ApiError && e.status === 401 && token) {
      const renewed = await auth.refreshNow(); // 실패하면 null: 토큰 없이(익명으로) 다시 시도한다
      return rawRequest(method, url, { ...options, token: renewed });
    }
    throw e;
  }
}

export const getOptions = () => request('GET', '/api/puzzles/options');

export function listPuzzles({ difficulty, topic, size } = {}) {
  const params = new URLSearchParams({ pageSize: '50' });
  if (difficulty) params.set('difficulty', difficulty);
  if (topic) params.set('topic', topic);
  if (size) params.set('size', String(size));
  return request('GET', `/api/puzzles?${params}`);
}

export const getPuzzle = (id) => request('GET', `/api/puzzles/${id}`);
export const startPuzzle = (id) => request('POST', `/api/puzzles/${id}/start`);
export const check = (id, session, answers) =>
  request('POST', `/api/puzzles/${id}/check`, { session, body: { answers } });
export const hint = (id, session, entryId) =>
  request('POST', `/api/puzzles/${id}/hint`, { session, body: { entryId } });
export const definitionHint = (id, session, entryId) =>
  request('POST', `/api/puzzles/${id}/definition-hint`, { session, body: { entryId } });
export const reveal = (id, session) => request('POST', `/api/puzzles/${id}/reveal`, { session, body: {} });
export const wordCard = (wordId, session) => request('GET', `/api/words/${wordId}`, { session });
export const getMe = () => request('GET', '/api/me');

export const getProgress = ({ page = 0, pageSize = 20 } = {}) =>
  request('GET', `/api/me/progress?page=${page}&pageSize=${pageSize}`);

export const getWrongAnswers = ({ page = 0, pageSize = 20, sort = 'recent' } = {}) =>
  request('GET', `/api/me/wrong-answers?page=${page}&pageSize=${pageSize}&sort=${sort}`);
