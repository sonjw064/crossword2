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
  SESSION_FORBIDDEN: '다른 사용자의 풀이예요. 퍼즐을 다시 시작해 주세요.',
  PUZZLE_NOT_FOUND: '퍼즐을 찾을 수 없어요.',
  CONCURRENT_UPDATE: '요청이 겹쳤어요. 다시 한 번 눌러 주세요.',
  CARD_NOT_AVAILABLE: '맞힌 단어만 카드를 볼 수 있어요.',
  INVALID_CREDENTIALS: '이메일 또는 비밀번호가 맞지 않아요.',
  EMAIL_TAKEN: '이미 가입된 이메일이에요.',
  RATE_LIMITED: '요청이 너무 많아요. 잠시 후 다시 시도해 주세요.',
  VALIDATION_FAILED: '입력한 내용을 확인해 주세요.',
  UNAUTHORIZED: '로그인이 필요해요.',
  INVALID_REFRESH_TOKEN: '로그인이 만료됐어요. 다시 로그인해 주세요.',
  MEMBER_ONLY: '회원만 사용할 수 있어요.',
  INVALID_ATTACHMENT: '스크린샷은 PNG 또는 JPEG 이미지(최대 2MB)만 첨부할 수 있어요.',
  ATTACHMENT_TOO_LARGE: '스크린샷이 너무 커요. 2MB 이하로 올려 주세요.',
  MALFORMED_REQUEST: '요청 형식이 올바르지 않아요.',
  GUEST_MIGRATED: '이 게스트는 이미 회원으로 이전됐어요. 다시 로그인해 주세요.',
  BATTLE_IN_PROGRESS: '대련 중에는 힌트, 정답 보기, 단어 카드를 쓸 수 없어요.',
  MATCH_NOT_ENDED: '아직 경기가 끝나지 않았어요.',
  REPLY_NOT_FOUND: '답변을 찾을 수 없어요.',
};

/** 토큰/세션 처리 없이 요청 하나를 보내고 JSON을 돌려준다. 실패하면 ApiError. */
export async function rawRequest(method, url, { body, session, token } = {}) {
  const headers = { Accept: 'application/json' };
  // FormData는 브라우저가 경계(boundary)가 포함된 Content-Type을 직접 붙이므로 JSON으로 바꾸거나 헤더를 지정하지 않는다
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData;
  if (body !== undefined && !isForm) headers['Content-Type'] = 'application/json';
  if (session) headers['X-Play-Session'] = session;
  if (token) headers.Authorization = `Bearer ${token}`;
  let response;
  try {
    response = await fetch(url, { method, headers, body: body === undefined || isForm ? body : JSON.stringify(body) });
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
