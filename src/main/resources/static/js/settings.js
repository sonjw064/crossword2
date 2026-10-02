// 단어 뜻 표시 설정 (게스트: 브라우저 localStorage). 회원 서버 저장은 2단계.
const KEY = 'crossword.meaningMode';

export const MEANING_MODES = [
  { value: 'off', label: '끔', hint: '단어 카드를 보여주지 않아요' },
  { value: 'meaning', label: '뜻만', hint: '맞힌 단어의 영어와 한국어 뜻' },
  { value: 'full', label: '뜻 + 정의', hint: '품사와 영어 정의까지' },
];

export function getMeaningMode() {
  try {
    const value = localStorage.getItem(KEY);
    if (MEANING_MODES.some((m) => m.value === value)) return value;
  } catch {
    // 저장소를 쓸 수 없으면 기본값을 쓴다
  }
  return 'meaning';
}

export function setMeaningMode(value) {
  try {
    localStorage.setItem(KEY, value);
  } catch {
    // 저장 실패는 무시한다 (이번 방문에서만 유지되지 않음)
  }
}
