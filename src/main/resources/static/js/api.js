import { rawRequest, ApiError } from './http.js';
import * as auth from './auth.js';

export { ApiError };

/** 로그인(게스트 포함) 상태면 토큰을 붙이고, 401이면 한 번 갱신해 다시 시도한다. */
async function request(method, url, options = {}) {
  const token = await auth.getAccessToken();
  try {
    return await rawRequest(method, url, { ...options, token });
  } catch (e) {
    // MEMBER_ONLY는 로그인했지만 게스트라서 받은 401이다: 토큰을 갱신해도 달라지지 않으므로 다시 시도하지 않는다
    if (e instanceof ApiError && e.status === 401 && e.code !== 'MEMBER_ONLY' && token) {
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
export const createRoom = (settings) => request('POST', '/api/rooms', { body: settings });
export const getMe = () => request('GET', '/api/me');

export const getProgress = ({ page = 0, pageSize = 20 } = {}) =>
  request('GET', `/api/me/progress?page=${page}&pageSize=${pageSize}`);

export const getWrongAnswers = ({ page = 0, pageSize = 20, sort = 'recent' } = {}) =>
  request('GET', `/api/me/wrong-answers?page=${page}&pageSize=${pageSize}&sort=${sort}`);

/** 문의 작성. 스크린샷이 있으면 multipart(data JSON + screenshot), 없어도 같은 형식으로 보낸다. */
export function createFeedback({ data, screenshot }) {
  const form = new FormData();
  form.append('data', new Blob([JSON.stringify(data)], { type: 'application/json' }));
  if (screenshot) form.append('screenshot', screenshot);
  return request('POST', '/api/feedback', { body: form });
}

export const getMyFeedback = () => request('GET', '/api/feedback/mine');
export const getUnreadCount = () => request('GET', '/api/feedback/unread-count');
export const markReplyRead = (replyId) => request('PATCH', `/api/feedback/replies/${replyId}/read`);

/** 첨부는 인증이 필요해 <img src>로 직접 못 쓰므로 Blob으로 받는다(object URL은 화면이 만들고 해제한다). */
export async function getAttachmentBlob(feedbackId) {
  const token = await auth.getAccessToken();
  const response = await fetch(`/api/feedback/${feedbackId}/attachment`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) throw new ApiError(response.status, 'ATTACHMENT_NOT_FOUND', '스크린샷을 불러올 수 없어요.');
  return response.blob();
}
