import test from 'node:test';
import assert from 'node:assert/strict';
import {
  answerToSubmit, battleErrorText, clockOffset, countdownSeconds, medal, percent, phaseOf, remainingSeconds, scoreboard,
  solvedIds, submitFeedback,
} from '../../main/resources/static/js/battle-model.js';

const match = (extra = {}) => ({
  startsAtMs: 10_000, endsAtMs: 70_000, serverNowMs: 0, totalEntries: 4, ended: false,
  progress: [
    { playerId: 1, solved: 1, score: 30, finished: false, abandoned: false, rank: null },
    { playerId: 2, solved: 3, score: 90, finished: false, abandoned: false, rank: null },
    { playerId: 3, solved: 3, score: 90, finished: false, abandoned: true, rank: null },
  ],
  ...extra,
});
const room = (m = match(), extra = {}) => ({
  status: 'PLAYING',
  players: [
    { playerId: 1, nickname: '가', host: true, ready: true, connected: true },
    { playerId: 2, nickname: '나', host: false, ready: false, connected: false },
  ],
  match: m,
  ...extra,
});

test('clockOffset: 서버 시각에서 내 시각을 뺀다', () => {
  assert.equal(clockOffset({ serverNowMs: 5000 }, 4200), 800);
});

test('phaseOf: 카운트다운 → 진행 → 시간 끝 → 종료', () => {
  const r = room();
  assert.equal(phaseOf(r, 9_999), 'countdown');
  assert.equal(phaseOf(r, 10_000), 'playing');
  assert.equal(phaseOf(r, 69_999), 'playing');
  assert.equal(phaseOf(r, 70_000), 'timeup');
  assert.equal(phaseOf(room(match({ ended: true })), 1), 'ended');
  assert.equal(phaseOf(room(match(), { status: 'ENDED' }), 1), 'ended');
  assert.equal(phaseOf({ status: 'WAITING', match: null }, 1), null);
});

test('countdownSeconds / remainingSeconds: 올림하고 0 밑으로 내려가지 않는다', () => {
  const m = match();
  assert.equal(countdownSeconds(m, 7_001), 3);
  assert.equal(countdownSeconds(m, 9_999), 1);
  assert.equal(countdownSeconds(m, 12_000), 0);
  assert.equal(remainingSeconds(m, 10_000), 60);
  assert.equal(remainingSeconds(m, 69_500), 1);
  assert.equal(remainingSeconds(m, 90_000), 0);
});

test('percent', () => {
  assert.equal(percent(1, 4), 25);
  assert.equal(percent(0, 0), 0);
  assert.equal(percent(2, 3), 67);
});

test('scoreboard: 진행 중에는 점수 → 맞힌 수 → 입장 순서', () => {
  const rows = scoreboard(room(), 2);
  assert.deepEqual(rows.map((r) => r.playerId), [2, 3, 1]);
  assert.equal(rows[0].you, true);
  assert.equal(rows[0].nickname, '나');
  assert.equal(rows[0].connected, false);
  assert.equal(rows[0].percent, 75);
});

test('scoreboard: 방에서 나간 사람은 (나감)으로 표시하고 점수는 유지한다', () => {
  const rows = scoreboard(room(), 1);
  const gone = rows.find((r) => r.playerId === 3);
  assert.equal(gone.nickname, '(나감)');
  assert.equal(gone.left, true);
  assert.equal(gone.score, 90);
});

test('scoreboard: 끝나면 서버가 정한 순위대로', () => {
  const ended = match({
    ended: true,
    progress: [
      { playerId: 1, solved: 4, score: 140, finished: true, abandoned: false, rank: 2 },
      { playerId: 2, solved: 4, score: 140, finished: true, abandoned: false, rank: 1 },
    ],
  });
  assert.deepEqual(scoreboard(room(ended), 1).map((r) => r.playerId), [2, 1]);
});

test('scoreboard: 경기가 없으면 빈 목록', () => {
  assert.deepEqual(scoreboard({ players: [], match: null }, 1), []);
});

test('medal', () => {
  assert.equal(medal(1), '🥇');
  assert.equal(medal(3), '🥉');
  assert.equal(medal(4), '4위');
});

test('submitFeedback', () => {
  assert.deepEqual(submitFeedback({ status: 'CORRECT', gained: 50, bonus: 0 }), { kind: 'ok', text: '정답! +50점' });
  assert.match(submitFeedback({ status: 'CORRECT', gained: 30, bonus: 100 }).text, /보너스 \+100/);
  assert.equal(submitFeedback({ status: 'WRONG' }).kind, 'bad');
  assert.match(submitFeedback({ status: 'ALREADY_SOLVED' }).text, /이미/);
  assert.match(submitFeedback({ status: 'INCOMPLETE' }).text, /채워/);
});

test('answerToSubmit: 다 채웠고 새로운 답일 때만 보낸다', () => {
  const base = { answer: 'cat', entryId: 1, solved: new Set(), pending: new Set(), lastTried: new Map() };
  assert.equal(answerToSubmit(base), 'cat');
  assert.equal(answerToSubmit({ ...base, answer: null }), null);
  assert.equal(answerToSubmit({ ...base, solved: new Set([1]) }), null);
  assert.equal(answerToSubmit({ ...base, pending: new Set([1]) }), null);
  assert.equal(answerToSubmit({ ...base, lastTried: new Map([[1, 'cat']]) }), null);
  assert.equal(answerToSubmit({ ...base, lastTried: new Map([[1, 'cot']]) }), 'cat');
});

test('solvedIds', () => {
  assert.deepEqual([...solvedIds([{ entryId: 3, word: 'a' }, { entryId: 5, word: 'b' }])], [3, 5]);
  assert.equal(solvedIds(undefined).size, 0);
});

test('battleErrorText: 아는 코드만 문구를 돌려준다', () => {
  assert.match(battleErrorText('MATCH_NOT_STARTED'), /카운트다운/);
  assert.equal(battleErrorText('SOMETHING_ELSE'), null);
});
