export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

const MESSAGES = {
  NETWORK: '서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.',
  SESSION_FINISHED: '이미 끝난 퍼즐이에요.',
  SESSION_NOT_FOUND: '풀이 세션을 찾을 수 없어요. 퍼즐을 다시 시작해 주세요.',
  PUZZLE_NOT_FOUND: '퍼즐을 찾을 수 없어요.',
  CONCURRENT_UPDATE: '요청이 겹쳤어요. 다시 한 번 눌러 주세요.',
  CARD_NOT_AVAILABLE: '맞힌 단어만 카드를 볼 수 있어요.',
};

async function request(method, url, { body, session } = {}) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (session) headers['X-Play-Session'] = session;
  let response;
  try {
    response = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, 'NETWORK', MESSAGES.NETWORK);
  }
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    // JSON이 아닌 응답은 아래 오류 처리로 넘긴다
  }
  if (!response.ok) {
    const code = data?.code ?? 'ERROR';
    throw new ApiError(response.status, code, MESSAGES[code] ?? '문제가 생겼어요. 잠시 후 다시 시도해 주세요.');
  }
  return data;
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
