// 대련(레이스) 화면과 무관한 순수 로직. (node --test 로 테스트: src/test/js)

/** 서버 시각 - 내 시각. 방송을 받은 순간의 내 시계로 서버 시계를 맞춘다. */
export function clockOffset(match, localNowMs) {
  return match.serverNowMs - localNowMs;
}

/** 'countdown' → 'playing' → 'timeup'(시간 끝, 서버가 곧 종료 알림) → 'ended'. 경기가 없으면 null. */
export function phaseOf(room, serverNowMs) {
  const match = room?.match;
  if (!match) return null;
  if (match.ended || room.status === 'ENDED') return 'ended';
  if (serverNowMs < match.startsAtMs) return 'countdown';
  if (serverNowMs >= match.endsAtMs) return 'timeup';
  return 'playing';
}

export function countdownSeconds(match, serverNowMs) {
  return Math.max(0, Math.ceil((match.startsAtMs - serverNowMs) / 1000));
}

export function remainingSeconds(match, serverNowMs) {
  return Math.max(0, Math.ceil((match.endsAtMs - serverNowMs) / 1000));
}

export function percent(solved, total) {
  return total > 0 ? Math.round((solved / total) * 100) : 0;
}

/**
 * 점수판 줄. 끝났으면 최종 순위, 진행 중이면 점수 → 맞힌 개수 → 입장 순서로 정렬한다.
 * 닉네임과 연결 상태는 방 참가자 목록에서 가져온다(나간 사람은 목록에 없어 '(나감)'으로 표시한다).
 */
export function scoreboard(room, me) {
  const match = room?.match;
  if (!match) return [];
  const byId = new Map(room.players.map((p) => [p.playerId, p]));
  const rows = match.progress.map((p) => {
    const player = byId.get(p.playerId);
    return {
      playerId: p.playerId,
      nickname: player?.nickname ?? '(나감)',
      host: !!player?.host,
      connected: player ? player.connected : false,
      left: !player,
      solved: p.solved,
      total: match.totalEntries,
      percent: percent(p.solved, match.totalEntries),
      score: p.score,
      finished: p.finished,
      abandoned: p.abandoned,
      rank: p.rank,
      you: p.playerId === me,
    };
  });
  rows.sort((a, b) => {
    if (a.rank !== null && b.rank !== null) return a.rank - b.rank;
    return b.score - a.score || b.solved - a.solved || a.playerId - b.playerId;
  });
  return rows;
}

export function medal(rank) {
  return { 1: '🥇', 2: '🥈', 3: '🥉' }[rank] ?? `${rank}위`;
}

/** 제출 응답을 화면 문구로. kind: ok | bad | info */
export function submitFeedback(ack) {
  switch (ack.status) {
    case 'CORRECT': {
      const bonus = ack.bonus > 0 ? ` · 완성 보너스 +${ack.bonus}` : '';
      return { kind: 'ok', text: `정답! +${ack.gained}점${bonus}` };
    }
    case 'WRONG': return { kind: 'bad', text: '틀렸어요. 다시 생각해 보세요.' };
    case 'ALREADY_SOLVED': return { kind: 'info', text: '이미 맞힌 단어예요.' };
    default: return { kind: 'info', text: '글자를 모두 채워 주세요.' };
  }
}

/**
 * 방금 입력으로 단어가 다 채워졌고 아직 안 맞혔으며, 응답을 기다리는 중이 아니고, 같은 답을 이미 틀린 적이 없으면 보낼 답을 돌려준다.
 * (틀린 답을 계속 다시 보내 제출 횟수 제한에 걸리지 않게 한다.)
 */
export function answerToSubmit({ answer, solved, entryId, pending, lastTried }) {
  if (!answer || solved.has(entryId) || pending.has(entryId)) return null;
  if (lastTried.get(entryId) === answer) return null;
  return answer;
}

/** 서버가 알려준 내가 맞힌 단어들로 칸을 복원할 때 쓸 항목 ID 집합. */
export function solvedIds(words) {
  return new Set((words ?? []).map((w) => w.entryId));
}

const ERROR_TEXT = {
  MATCH_NOT_STARTED: '아직 시작 전이에요. 카운트다운이 끝나면 풀 수 있어요.',
  MATCH_OVER: '경기가 끝났어요.',
  RATE_LIMITED: '너무 빨라요. 잠시 후 다시 시도해 주세요.',
  INVALID_ENTRY: '알 수 없는 단어예요.',
  INVALID_STATE: '지금은 제출할 수 없어요.',
};

export function battleErrorText(code) {
  return ERROR_TEXT[code] ?? null;
}
