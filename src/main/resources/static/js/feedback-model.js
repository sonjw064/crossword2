// 문의 화면의 순수 로직(화면과 무관): 입력 검증, 선택지, 읽지 않음 뱃지 표기. (node --test 로 테스트: src/test/js)
// 서버가 같은 규칙으로 다시 검증하므로 여기는 사용자에게 빨리 알려주기 위한 것이다.

export const LIMITS = {
  title: 100,
  content: 2000,
  fileBytes: 2 * 1024 * 1024,
  fileTypes: ['image/png', 'image/jpeg'],
};

export const TYPES = [
  { value: 'BUG', label: '버그 신고' },
  { value: 'WORD_ERROR', label: '단어/뜻 오류' },
  { value: 'SUGGESTION', label: '기능 제안' },
  { value: 'GENERAL', label: '일반 문의' },
];

export const REASONS = [
  { value: 'WRONG_MEANING', label: '뜻이 틀림' },
  { value: 'MULTIPLE_ANSWERS', label: '정답이 여러 개' },
  { value: 'TYPO', label: '오타' },
  { value: 'OTHER', label: '기타' },
];

export const STATUS_LABEL = { RECEIVED: '접수', IN_REVIEW: '확인 중', RESOLVED: '처리 완료', REJECTED: '반려' };

export const labelOf = (list, value) => list.find((item) => item.value === value)?.label ?? value;

const CONTROL = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/; // 줄바꿈/탭을 뺀 제어 문자
const ANY_CONTROL = /[\u0000-\u001F\u007F]/;

/** @returns {{ok: boolean, errors: Record<string,string>}} 필드별 오류 문구. */
export function validateFeedback({ type, title, content, file }) {
  const errors = {};
  if (!TYPES.some((t) => t.value === type)) errors.type = '문의 유형을 골라 주세요.';

  const t = (title ?? '').trim();
  if (!t) errors.title = '제목을 입력해 주세요.';
  else if (t.length > LIMITS.title) errors.title = `제목은 ${LIMITS.title}자 이하로 입력해 주세요.`;
  else if (ANY_CONTROL.test(t)) errors.title = '제목에 사용할 수 없는 문자가 있어요.';

  const c = (content ?? '').trim();
  if (!c) errors.content = '내용을 입력해 주세요.';
  else if (c.length > LIMITS.content) errors.content = `내용은 ${LIMITS.content}자 이하로 입력해 주세요.`;
  else if (CONTROL.test(c)) errors.content = '내용에 사용할 수 없는 문자가 있어요.';

  const fileError = validateFile(file);
  if (fileError) errors.file = fileError;
  return { ok: Object.keys(errors).length === 0, errors };
}

/** 첨부는 선택 사항이다. 없으면 오류가 아니다. */
export function validateFile(file) {
  if (!file) return '';
  if (!LIMITS.fileTypes.includes(file.type)) return '스크린샷은 PNG 또는 JPEG 이미지만 첨부할 수 있어요.';
  if (file.size > LIMITS.fileBytes) return '스크린샷은 2MB 이하만 첨부할 수 있어요.';
  return '';
}

/** 헤더의 읽지 않음 뱃지 문구. 0 이하면 빈 문자열(뱃지 숨김). */
export function unreadBadge(count) {
  if (!Number.isFinite(count) || count <= 0) return '';
  return count > 99 ? '99+' : String(Math.floor(count));
}

/** 서버가 받는 화면 크기 형식(예: 390x844@3)으로 만든다. 값이 이상하면 null. */
export function screenInfo({ width, height, pixelRatio } = {}) {
  const w = Math.round(width);
  const h = Math.round(height);
  if (![w, h].every((n) => Number.isInteger(n) && n >= 10 && n <= 99999)) return null;
  const ratio = Number.isFinite(pixelRatio) && pixelRatio >= 1 && pixelRatio < 10 ? `@${Number(pixelRatio.toFixed(2))}` : '';
  return `${w}x${h}${ratio}`;
}

/** 서버 요청 본문의 undefined/빈 문자열 필드를 빼서 보낸다. */
export function compact(data) {
  return Object.fromEntries(Object.entries(data).filter(([, v]) => v !== undefined && v !== null && v !== ''));
}

/** 퍼즐 내 빠른 신고 본문. 제목/내용은 서버가 채우고, 덧붙이는 말이 있을 때만 content를 보낸다. */
export function quickReportBody({ puzzleId, wordId, reason, comment, screen }) {
  return compact({ type: 'WORD_ERROR', puzzleId, wordId, reason, content: (comment ?? '').trim(), screen });
}
