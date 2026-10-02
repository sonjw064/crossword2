// 그리드 로직. 화면(DOM)과 무관한 순수 함수만 둔다. (node --test 로 테스트: src/test/js)

export const ACROSS = 'ACROSS';
export const DOWN = 'DOWN';

/** 서버가 준 퍼즐(구조와 힌트만)에서 칸/항목 조회용 모델을 만든다. */
export function buildModel(puzzle) {
  const size = puzzle.size;
  const cells = Array.from({ length: size }, () => Array.from({ length: size }, () => null));
  const entries = new Map();
  const order = [];
  for (const e of puzzle.entries) {
    const dr = e.direction === DOWN ? 1 : 0;
    const dc = e.direction === ACROSS ? 1 : 0;
    const coords = [];
    for (let i = 0; i < e.length; i++) {
      const r = e.row + dr * i;
      const c = e.col + dc * i;
      coords.push([r, c]);
      const cell = cells[r][c] ?? (cells[r][c] = { row: r, col: c, number: null, across: null, down: null });
      if (e.direction === ACROSS) cell.across = e.id;
      else cell.down = e.id;
    }
    cells[e.row][e.col].number = e.number;
    entries.set(e.id, { ...e, coords });
    order.push(e.id);
  }
  return { size, cells, entries, order };
}

/** 입력 상태: 칸별 글자, 잠금(맞힘/힌트/공개), 잠금 종류. */
export function createState(model) {
  const grid = (fill) => Array.from({ length: model.size }, () => Array.from({ length: model.size }, () => fill));
  return { letters: grid(''), locked: grid(false), kind: grid('') };
}

export function entryIdAt(model, r, c, dir) {
  const cell = model.cells[r]?.[c];
  if (!cell) return null;
  const preferred = dir === ACROSS ? cell.across : cell.down;
  if (preferred !== null) return preferred;
  return dir === ACROSS ? cell.down : cell.across;
}

function selectionAt(model, r, c, dir) {
  const entryId = entryIdAt(model, r, c, dir);
  if (entryId === null) return null;
  return { row: r, col: c, dir: model.entries.get(entryId).direction, entryId };
}

/** 칸을 클릭했을 때의 선택. 같은 칸을 다시 클릭하면 (두 방향이 있을 때) 방향을 바꾼다. */
export function select(model, current, r, c) {
  const cell = model.cells[r]?.[c];
  if (!cell) return current;
  let dir = current ? current.dir : ACROSS;
  if (current && current.row === r && current.col === c && cell.across !== null && cell.down !== null) {
    dir = current.dir === ACROSS ? DOWN : ACROSS;
  }
  return selectionAt(model, r, c, dir);
}

/** 힌트 목록에서 항목을 골랐을 때의 선택. 비어 있는 첫 칸(없으면 첫 칸)에 커서를 둔다. */
export function selectEntry(model, entryId, state) {
  const entry = model.entries.get(entryId);
  const target = (state && entry.coords.find(([r, c]) => !state.letters[r][c])) ?? entry.coords[0];
  return { row: target[0], col: target[1], dir: entry.direction, entryId };
}

function indexInEntry(model, sel) {
  return model.entries.get(sel.entryId).coords.findIndex(([r, c]) => r === sel.row && c === sel.col);
}

function at(model, sel, index) {
  const [row, col] = model.entries.get(sel.entryId).coords[index];
  return { ...sel, row, col };
}

/** 방향키 이동: 빈 칸(막힌 칸)은 건너뛰고 다음 글자 칸으로 간다. 이동한 축의 방향을 우선한다. */
export function arrow(model, sel, dr, dc) {
  let r = sel.row + dr;
  let c = sel.col + dc;
  while (r >= 0 && c >= 0 && r < model.size && c < model.size) {
    if (model.cells[r][c]) {
      return selectionAt(model, r, c, dr !== 0 ? DOWN : ACROSS) ?? sel;
    }
    r += dr;
    c += dc;
  }
  return sel;
}

/** 다음/이전 항목(번호 순, 가로 먼저)으로 이동한다. 끝에서는 반대편으로 돌아온다. */
export function cycleEntry(model, sel, step, state) {
  const n = model.order.length;
  const index = model.order.indexOf(sel.entryId);
  return selectEntry(model, model.order[(index + step + n) % n], state);
}

/** 글자를 입력하고 같은 항목의 다음 (잠기지 않은) 칸으로 커서를 옮긴다. */
export function typeLetter(model, state, sel, ch) {
  if (!/^[a-z]$/i.test(ch)) return sel;
  if (!state.locked[sel.row][sel.col]) state.letters[sel.row][sel.col] = ch.toLowerCase();
  const coords = model.entries.get(sel.entryId).coords;
  for (let i = indexInEntry(model, sel) + 1; i < coords.length; i++) {
    if (!state.locked[coords[i][0]][coords[i][1]]) return at(model, sel, i);
  }
  return sel;
}

/** Backspace: 현재 칸에 글자가 있으면 지우고, 없으면 이전 (잠기지 않은) 칸으로 가서 지운다. */
export function backspace(model, state, sel) {
  const { row, col } = sel;
  if (state.letters[row][col] && !state.locked[row][col]) {
    state.letters[row][col] = '';
    return sel;
  }
  const coords = model.entries.get(sel.entryId).coords;
  for (let i = indexInEntry(model, sel) - 1; i >= 0; i--) {
    const [r, c] = coords[i];
    if (!state.locked[r][c]) {
      state.letters[r][c] = '';
      return at(model, sel, i);
    }
  }
  return sel;
}

/** 항목의 칸이 모두 채워졌으면 답을 문자열로, 아니면 null. */
export function answerOf(model, state, entryId) {
  const letters = model.entries.get(entryId).coords.map(([r, c]) => state.letters[r][c]);
  return letters.every(Boolean) ? letters.join('') : null;
}

/** 아직 맞히지 않았고 글자를 모두 채운 항목들의 채점 요청 목록. */
export function answersToCheck(model, state, solved) {
  const answers = [];
  for (const id of model.order) {
    if (solved.has(id)) continue;
    const answer = answerOf(model, state, id);
    if (answer) answers.push({ entryId: id, answer });
  }
  return answers;
}

function lock(state, r, c, kind) {
  state.locked[r][c] = true;
  if (!state.kind[r][c] || kind === 'correct') state.kind[r][c] = kind;
}

/** 맞힌 항목의 칸을 잠근다. */
export function lockEntry(model, state, entryId) {
  for (const [r, c] of model.entries.get(entryId).coords) lock(state, r, c, 'correct');
}

/** 첫 글자 힌트 반영. 이미 잠긴 칸이면 false (공개된 글자가 없으므로 힌트를 쓸 필요가 없다). */
export function applyHint(model, state, entryId, letter) {
  const [r, c] = model.entries.get(entryId).coords[0];
  if (state.locked[r][c]) return false;
  state.letters[r][c] = letter.toLowerCase();
  lock(state, r, c, 'hint');
  return true;
}

/** 정답 보기: 항목 전체를 단어로 채우고 잠근다. */
export function revealEntry(model, state, entryId, word) {
  model.entries.get(entryId).coords.forEach(([r, c], i) => {
    state.letters[r][c] = word[i].toLowerCase();
    if (!state.locked[r][c] || state.kind[r][c] === 'hint') {
      state.locked[r][c] = true;
      state.kind[r][c] = 'revealed';
    }
  });
}
