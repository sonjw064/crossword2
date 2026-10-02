// 대기실 화면과 무관한 순수 로직. (node --test 로 테스트: src/test/js)

// 서버(RoomCodes)와 같은 알파벳: 0/O/1/I 제외
const ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
const CODE_PATTERN = new RegExp(`^[${ALPHABET}]{6}$`);

export const MODE_LABEL = { RACE: '속도 대결', WORD_CLAIM: '단어 선점', COOP: '협동' };
export const DIFFICULTY_LABEL = { EASY: '쉬움', MEDIUM: '보통', HARD: '어려움' };

/** 입력 방 코드를 대문자로 정리해 형식이 맞으면 돌려주고, 아니면 null. */
export function parseRoomCode(raw) {
  if (typeof raw !== 'string') return null;
  const code = raw.trim().toUpperCase();
  return CODE_PATTERN.test(code) ? code : null;
}

/** 초대 링크 경로(/room/ABC234)에서 코드를 꺼낸다. 아니면 null. */
export function roomCodeFromPath(pathname) {
  const m = /^\/room\/([^/]+)\/?$/.exec(pathname ?? '');
  if (!m) return null;
  try {
    return parseRoomCode(decodeURIComponent(m[1]));
  } catch {
    return null;
  }
}

export function inviteUrl(origin, code) {
  return `${origin}/room/${code}`;
}

/** 새 스냅샷이 현재 것보다 새로울 때만 바꾼다(순서가 뒤바뀌어 온 옛 방송은 버린다). */
export function applySnapshot(current, incoming) {
  if (typeof incoming?.version !== 'number') return current;
  if (current && current.version > incoming.version) return current;
  return incoming;
}

export function isHost(room, playerId) {
  return !!room?.players.some((p) => p.playerId === playerId && p.host);
}

/** 시작 버튼을 누를 수 있는지와, 못 누르면 이유. */
export function startState(room, playerId) {
  if (!room || room.status !== 'WAITING') return { enabled: false, reason: '' };
  if (!isHost(room, playerId)) return { enabled: false, reason: '방장만 시작할 수 있어요.' };
  if (room.players.length < 2) return { enabled: false, reason: '2명 이상 모여야 시작할 수 있어요.' };
  const waiting = room.players.filter((p) => !p.host && (!p.ready || !p.connected));
  if (waiting.length) return { enabled: false, reason: `${waiting.map((p) => p.nickname).join(', ')} 님을 기다리는 중이에요.` };
  return { enabled: true, reason: '' };
}

const ERROR_TEXT = {
  ROOM_NOT_FOUND: '방을 찾을 수 없어요. 코드를 다시 확인해 주세요.',
  ROOM_FULL: '방이 가득 찼어요.',
  ROOM_IN_PROGRESS: '이미 경기가 시작된 방이에요.',
  ALREADY_IN_ROOM: '이미 다른 방에 있어요. 먼저 그 방에서 나가 주세요.',
  NOT_HOST: '방장만 할 수 있어요.',
  NOT_IN_ROOM: '이 방의 참가자가 아니에요.',
  NOT_ENOUGH_PLAYERS: '2명 이상 모여야 시작할 수 있어요.',
  NOT_ALL_READY: '모두 준비해야 시작할 수 있어요.',
  SETTINGS_INVALID: '설정 값이 올바르지 않아요.',
  MODE_NOT_AVAILABLE: '아직 준비 중인 모드예요.',
  NO_PUZZLE_AVAILABLE: '이 조건에 맞는 퍼즐이 없어요. 설정을 바꿔 보세요.',
  ROOM_LIMIT: '지금은 방을 더 만들 수 없어요. 잠시 후 다시 시도해 주세요.',
  RATE_LIMITED: '요청이 너무 많아요. 잠시 후 다시 시도해 주세요.',
  INVALID_STATE: '지금은 할 수 없는 동작이에요.',
  MALFORMED_REQUEST: '요청 형식이 올바르지 않아요.',
  MATCH_NOT_ENDED: '아직 경기가 끝나지 않았어요.',
};

/** 서버가 보낸 오류를 사용자 문구로. ALREADY_IN_ROOM은 서버 메시지에 든 방 코드를 덧붙인다. */
export function roomErrorText(error) {
  const base = ERROR_TEXT[error?.code] ?? '문제가 생겼어요. 잠시 후 다시 시도해 주세요.';
  const m = error?.code === 'ALREADY_IN_ROOM' ? new RegExp(`[${ALPHABET}]{6}`).exec(error.message ?? '') : null;
  return m ? `${base} (${m[0]})` : base;
}

/** 연결 상태 배너 문구(연결됨이면 빈 문자열). */
export function connectionText(state) {
  if (state === 'connected') return '';
  if (state === 'connecting') return '연결하는 중…';
  return '연결이 끊겼어요. 다시 연결하는 중…';
}
