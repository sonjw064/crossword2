export const DIFFICULTY_LABEL = { EASY: '쉬움', MEDIUM: '보통', HARD: '어려움' };
export const DIRECTION_LABEL = { ACROSS: '가로', DOWN: '세로' };
export const STATUS_LABEL = { COMPLETED: '완료', GAVE_UP: '포기' };
export const POS_LABEL = { NOUN: '명사', VERB: '동사', ADJECTIVE: '형용사', ADVERB: '부사', OTHER: '기타' };

const TOPIC_LABEL = {
  animals: '동물',
  food: '음식',
  school: '학교',
  nature: '자연',
  home: '집',
  feelings: '감정',
};

export function topicLabel(topic) {
  if (!topic) return '전체 주제';
  return TOPIC_LABEL[topic] ?? topic;
}

export function formatDateTime(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' });
}

export function formatTime(totalSeconds) {
  const s = Math.max(0, Math.floor(totalSeconds));
  const mm = String(Math.floor(s / 60)).padStart(2, '0');
  const ss = String(s % 60).padStart(2, '0');
  return `${mm}:${ss}`;
}
