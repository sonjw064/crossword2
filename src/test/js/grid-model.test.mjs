import test from 'node:test';
import assert from 'node:assert/strict';
import * as M from '../../main/resources/static/js/grid-model.js';

// 5x5 고정 사례:
//   c a t . .
//   o . u . .
//   w e b . .
const puzzle = {
  size: 5,
  entries: [
    { id: 1, number: 1, direction: 'ACROSS', row: 0, col: 0, length: 3 },
    { id: 2, number: 1, direction: 'DOWN', row: 0, col: 0, length: 3 },
    { id: 3, number: 2, direction: 'DOWN', row: 0, col: 2, length: 3 },
    { id: 4, number: 3, direction: 'ACROSS', row: 2, col: 0, length: 3 },
  ],
};
const fresh = () => {
  const model = M.buildModel(puzzle);
  return { model, state: M.createState(model) };
};
const fill = (state, word, coords) => coords.forEach(([r, c], i) => (state.letters[r][c] = word[i]));

test('buildModel: 칸, 번호, 방향별 항목', () => {
  const { model } = fresh();
  assert.equal(model.cells[0][0].number, 1);
  assert.equal(model.cells[0][0].across, 1);
  assert.equal(model.cells[0][0].down, 2);
  assert.equal(model.cells[1][0].across, null);
  assert.equal(model.cells[1][0].down, 2);
  assert.equal(model.cells[1][1], null);
  assert.equal(model.cells[2][2].across, 4);
  assert.equal(model.cells[2][2].down, 3);
  assert.equal(model.cells[0][2].number, 2);
  assert.deepEqual(model.order, [1, 2, 3, 4]);
});

test('select: 같은 칸을 다시 누르면 방향 전환, 한 방향뿐이면 그 방향', () => {
  const { model } = fresh();
  let sel = M.select(model, null, 0, 0);
  assert.deepEqual([sel.dir, sel.entryId], ['ACROSS', 1]);
  sel = M.select(model, sel, 0, 0);
  assert.deepEqual([sel.dir, sel.entryId], ['DOWN', 2]);
  sel = M.select(model, sel, 1, 0); // 세로만 있는 칸
  assert.deepEqual([sel.dir, sel.entryId], ['DOWN', 2]);
  sel = M.select(model, sel, 0, 1); // 가로만 있는 칸
  assert.deepEqual([sel.dir, sel.entryId], ['ACROSS', 1]);
  assert.equal(M.select(model, sel, 1, 1), sel); // 막힌 칸은 무시
});

test('selectEntry: 비어 있는 첫 칸에 커서', () => {
  const { model, state } = fresh();
  state.letters[0][0] = 'c';
  const sel = M.selectEntry(model, 1, state);
  assert.deepEqual([sel.row, sel.col], [0, 1]);
  assert.deepEqual(
    [M.selectEntry(model, 3, state).row, M.selectEntry(model, 3, state).col],
    [0, 2],
  );
});

test('arrow: 막힌 칸을 건너뛰고, 끝에서는 제자리', () => {
  const { model } = fresh();
  let sel = M.select(model, null, 0, 1);
  sel = M.arrow(model, sel, 1, 0); // (1,1)은 막힘 → (2,1)
  assert.deepEqual([sel.row, sel.col, sel.dir], [2, 1, 'ACROSS']);
  const top = M.select(model, null, 0, 2);
  assert.equal(M.arrow(model, top, 0, 1), top);
  assert.equal(M.arrow(model, top, -1, 0), top);
  const right = M.arrow(model, M.select(model, null, 0, 0), 0, 1);
  assert.deepEqual([right.row, right.col, right.dir], [0, 1, 'ACROSS']);
});

test('cycleEntry: 번호 순으로 돌고 처음/끝에서 순환', () => {
  const { model, state } = fresh();
  let sel = M.selectEntry(model, 4, state);
  assert.equal(M.cycleEntry(model, sel, 1, state).entryId, 1);
  sel = M.selectEntry(model, 1, state);
  assert.equal(M.cycleEntry(model, sel, -1, state).entryId, 4);
  assert.equal(M.cycleEntry(model, sel, 1, state).entryId, 2);
});

test('typeLetter: 소문자로 저장하고 다음 칸으로, 영문 외 입력은 무시', () => {
  const { model, state } = fresh();
  let sel = M.selectEntry(model, 1, state);
  sel = M.typeLetter(model, state, sel, 'C');
  assert.equal(state.letters[0][0], 'c');
  assert.deepEqual([sel.row, sel.col], [0, 1]);
  const same = M.typeLetter(model, state, sel, '1');
  assert.equal(same, sel);
  assert.equal(M.typeLetter(model, state, sel, 'ㄱ'), sel);
  sel = M.typeLetter(model, state, sel, 'a');
  sel = M.typeLetter(model, state, sel, 't');
  assert.deepEqual([sel.row, sel.col], [0, 2]); // 마지막 칸에서는 제자리
  assert.equal(state.letters[0][2], 't');
});

test('typeLetter: 잠긴 칸은 덮어쓰지 않고 건너뛴다', () => {
  const { model, state } = fresh();
  state.letters[0][1] = 'a';
  state.locked[0][1] = true;
  let sel = M.selectEntry(model, 1, state);
  sel = M.typeLetter(model, state, sel, 'x'); // (0,0) 입력 후 잠긴 (0,1)을 건너뜀
  assert.deepEqual([sel.row, sel.col], [0, 2]);
  const onLocked = { ...sel, row: 0, col: 1 };
  M.typeLetter(model, state, onLocked, 'z');
  assert.equal(state.letters[0][1], 'a');
});

test('backspace: 지우고, 비어 있으면 이전 칸으로 가서 지움', () => {
  const { model, state } = fresh();
  fill(state, 'cat', model.entries.get(1).coords);
  let sel = M.select(model, null, 0, 2);
  sel = M.backspace(model, state, sel);
  assert.equal(state.letters[0][2], '');
  assert.deepEqual([sel.row, sel.col], [0, 2]);
  sel = M.backspace(model, state, sel); // 비어 있음 → (0,1)을 지우고 이동
  assert.equal(state.letters[0][1], '');
  assert.deepEqual([sel.row, sel.col], [0, 1]);
  const start = M.select(model, null, 0, 0);
  state.letters[0][0] = '';
  assert.equal(M.backspace(model, state, start), start);
});

test('backspace: 잠긴 칸은 지우지 않는다', () => {
  const { model, state } = fresh();
  fill(state, 'cat', model.entries.get(1).coords);
  M.lockEntry(model, state, 1);
  const sel = M.select(model, null, 0, 2);
  assert.equal(M.backspace(model, state, sel), sel);
  assert.equal(state.letters[0][2], 't');
});

test('answerOf / answersToCheck: 다 채운 항목만, 맞힌 항목 제외', () => {
  const { model, state } = fresh();
  fill(state, 'ca', model.entries.get(1).coords);
  assert.equal(M.answerOf(model, state, 1), null);
  fill(state, 'cat', model.entries.get(1).coords);
  fill(state, 'web', model.entries.get(4).coords);
  assert.deepEqual(M.answersToCheck(model, state, new Set()), [
    { entryId: 1, answer: 'cat' },
    { entryId: 4, answer: 'web' },
  ]);
  assert.deepEqual(M.answersToCheck(model, state, new Set([1])), [{ entryId: 4, answer: 'web' }]);
});

test('applyHint: 첫 글자를 공개·잠금, 이미 잠긴 칸이면 false', () => {
  const { model, state } = fresh();
  assert.equal(M.applyHint(model, state, 2, 'C'), true);
  assert.equal(state.letters[0][0], 'c');
  assert.equal(state.kind[0][0], 'hint');
  assert.equal(M.applyHint(model, state, 1, 'c'), false); // (0,0)은 이미 잠김
});

test('lockEntry 후 hint 표시는 correct가 되고, revealEntry는 힌트 칸까지 공개 처리', () => {
  const { model, state } = fresh();
  M.applyHint(model, state, 1, 'c');
  fill(state, 'cat', model.entries.get(1).coords);
  M.lockEntry(model, state, 1);
  assert.equal(state.kind[0][0], 'correct');
  M.applyHint(model, state, 4, 'w');
  M.revealEntry(model, state, 4, 'web');
  assert.deepEqual(model.entries.get(4).coords.map(([r, c]) => state.letters[r][c]), ['w', 'e', 'b']);
  assert.equal(state.kind[2][0], 'revealed');
  assert.equal(state.kind[0][0], 'correct'); // 이미 맞힌 칸의 표시는 유지
});
