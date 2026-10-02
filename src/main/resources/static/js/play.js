import { h, clear } from './dom.js';
import * as api from './api.js';
import * as M from './grid-model.js';
import { getMeaningMode } from './settings.js';
import { DIFFICULTY_LABEL, DIRECTION_LABEL, POS_LABEL, formatTime, topicLabel } from './labels.js';

export async function mountPlay(root, puzzleId, ctx, handle) {
  root.append(h('p', { class: 'muted' }, '퍼즐을 불러오는 중…'));
  let puzzle;
  let session;
  try {
    puzzle = await api.getPuzzle(puzzleId);
    session = (await api.startPuzzle(puzzleId)).sessionId;
  } catch (e) {
    if (handle.cancelled) return;
    ctx.toast(e.message);
    ctx.navigate('#/');
    return;
  }
  if (handle.cancelled) return;
  clear(root);

  const model = M.buildModel(puzzle);
  const state = M.createState(model);
  const solved = new Set();
  const wrongEntries = new Set();
  const definitions = new Map();
  const startedAt = Date.now();
  let sel = M.selectEntry(model, model.order[0], state);
  let counts = { hints: 0, wrong: 0 };
  let finished = null; // null | 'COMPLETED' | 'GAVE_UP'
  let revealedCards = null;
  let busy = false;

  // ---------- DOM ----------
  const timerEl = h('b', {}, '00:00');
  const hintEl = h('b', {}, '0');
  const wrongEl = h('b', {}, '0');
  const clueBar = h('div', { class: 'clue-bar' });
  const definitionEl = h('div', { class: 'definition', hidden: true });
  const cardEl = h('div', { class: 'word-card', hidden: true });
  const bannerEl = h('div', { class: 'banner', hidden: true });

  const cellEls = model.cells.map((row) => row.map(() => null));
  const gridEl = h('div', { class: 'grid', style: `--size:${model.size}`, role: 'grid', 'aria-label': '십자말풀이 판' });
  for (let r = 0; r < model.size; r++) {
    for (let c = 0; c < model.size; c++) {
      const cell = model.cells[r][c];
      if (!cell) {
        gridEl.append(h('div', { class: 'cell block' }));
        continue;
      }
      const el = h('div', { class: 'cell', 'data-r': r, 'data-c': c, role: 'gridcell' },
        cell.number !== null ? h('span', { class: 'num' }, cell.number) : null,
        h('span', { class: 'letter' }));
      cellEls[r][c] = el;
      gridEl.append(el);
    }
  }

  const kbd = h('input', {
    class: 'kbd', type: 'text', inputmode: 'text', autocapitalize: 'none', autocomplete: 'off',
    autocorrect: 'off', spellcheck: 'false', lang: 'en', 'aria-label': '글자 입력', tabindex: '-1',
  });

  const clueItems = new Map();
  function clueList(direction) {
    const list = h('ul', { class: 'clue-list' });
    for (const id of model.order) {
      const e = model.entries.get(id);
      if (e.direction !== direction) continue;
      const item = h('li', {},
        h('button', { type: 'button', class: 'clue', onclick: () => choose(M.selectEntry(model, id, state)) },
          h('span', { class: 'clue-no' }, e.number),
          h('span', { class: 'clue-text' }, e.clue),
          h('small', { class: 'muted' }, `${e.partOfSpeech ? POS_LABEL[e.partOfSpeech] + ' · ' : ''}${e.length}글자`)));
      clueItems.set(id, item);
      list.append(item);
    }
    return list;
  }

  const buttons = {
    check: h('button', { type: 'button', class: 'primary', onclick: doCheck }, '정답 체크'),
    hint: h('button', { type: 'button', onclick: doHint }, '첫 글자 힌트'),
    definition: h('button', { type: 'button', onclick: doDefinition }, '정의 힌트'),
    reveal: h('button', { type: 'button', class: 'danger', onclick: doReveal }, '정답 보기'),
  };

  root.append(
    h('section', { class: 'play' },
      h('div', { class: 'play-head' },
        h('a', { class: 'back', href: '#/' }, '← 목록'),
        h('span', { class: 'meta' }, `${DIFFICULTY_LABEL[puzzle.difficulty]} · ${topicLabel(puzzle.topic)} · ${puzzle.size}×${puzzle.size}`),
        h('span', { class: 'stats' }, '⏱ ', timerEl, ' · 힌트 ', hintEl, ' · 오답 ', wrongEl)),
      clueBar,
      h('div', { class: 'play-layout' },
        h('div', { class: 'board-col' },
          gridEl, kbd,
          h('div', { class: 'actions' }, Object.values(buttons)),
          definitionEl, cardEl, bannerEl),
        h('aside', { class: 'clues' },
          h('h2', {}, '가로'), clueList('ACROSS'),
          h('h2', {}, '세로'), clueList('DOWN')))),
  );

  // ---------- 그리기 ----------
  function paint() {
    const current = new Set(model.entries.get(sel.entryId).coords.map(([r, c]) => `${r},${c}`));
    const wrongCells = new Set();
    for (const id of wrongEntries) model.entries.get(id).coords.forEach(([r, c]) => wrongCells.add(`${r},${c}`));

    for (let r = 0; r < model.size; r++) {
      for (let c = 0; c < model.size; c++) {
        const el = cellEls[r][c];
        if (!el) continue;
        const key = `${r},${c}`;
        el.querySelector('.letter').textContent = state.letters[r][c];
        el.classList.toggle('selected', sel.row === r && sel.col === c);
        el.classList.toggle('in-word', current.has(key));
        el.classList.toggle('locked', state.locked[r][c]);
        el.classList.toggle('correct', state.kind[r][c] === 'correct');
        el.classList.toggle('hint', state.kind[r][c] === 'hint');
        el.classList.toggle('revealed', state.kind[r][c] === 'revealed');
        el.classList.toggle('wrong', wrongCells.has(key) && !state.locked[r][c]);
      }
    }
    for (const [id, item] of clueItems) {
      item.classList.toggle('active', id === sel.entryId);
      item.classList.toggle('solved', solved.has(id));
    }
    const entry = model.entries.get(sel.entryId);
    clear(clueBar).append(
      h('b', {}, `${entry.number} ${DIRECTION_LABEL[entry.direction]}`), ' ',
      h('span', {}, entry.clue), ' ',
      h('small', { class: 'muted' }, `${entry.partOfSpeech ? POS_LABEL[entry.partOfSpeech] + ' · ' : ''}${entry.length}글자`));
    const definition = definitions.get(sel.entryId);
    definitionEl.hidden = !definition;
    if (definition) definitionEl.textContent = `정의: ${definition}`;
    hintEl.textContent = counts.hints;
    wrongEl.textContent = counts.wrong;
    for (const b of Object.values(buttons)) b.disabled = busy || finished !== null;
  }

  function choose(next) {
    sel = next;
    paint();
    kbd.focus({ preventScroll: true });
  }

  // ---------- 입력 ----------
  function clearWrongAt(r, c) {
    const cell = model.cells[r][c];
    wrongEntries.delete(cell.across);
    wrongEntries.delete(cell.down);
  }

  function type(ch) {
    if (finished) return;
    clearWrongAt(sel.row, sel.col);
    sel = M.typeLetter(model, state, sel, ch);
    paint();
  }

  function erase() {
    if (finished) return;
    clearWrongAt(sel.row, sel.col);
    sel = M.backspace(model, state, sel);
    paint();
  }

  function onKeydown(e) {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    const arrows = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] };
    const onBoard = e.target === kbd || e.target === document.body;
    if (/^[a-zA-Z]$/.test(e.key) && (onBoard || e.target.tagName !== 'INPUT')) {
      e.preventDefault();
      type(e.key);
    } else if (e.key === 'Backspace' && onBoard) {
      e.preventDefault();
      erase();
    } else if (arrows[e.key] && onBoard) {
      e.preventDefault();
      choose(M.arrow(model, sel, ...arrows[e.key]));
    } else if (e.key === 'Tab' && onBoard) {
      e.preventDefault();
      choose(M.cycleEntry(model, sel, e.shiftKey ? -1 : 1, state));
    }
  }
  document.addEventListener('keydown', onKeydown);

  // 모바일 가상 키보드: keydown에 글자가 오지 않으므로 beforeinput으로 받는다
  kbd.addEventListener('beforeinput', (e) => {
    if (e.inputType === 'deleteContentBackward') {
      e.preventDefault();
      erase();
    } else if (e.inputType === 'insertText' && e.data) {
      e.preventDefault();
      for (const ch of e.data) type(ch);
    }
  });
  kbd.addEventListener('input', () => (kbd.value = ''));

  gridEl.addEventListener('click', (e) => {
    const el = e.target.closest('.cell[data-r]');
    if (!el) return;
    choose(M.select(model, sel, Number(el.dataset.r), Number(el.dataset.c)));
  });

  // ---------- 서버 동작 ----------
  async function run(action) {
    if (busy || finished) return;
    busy = true;
    paint();
    try {
      await action();
    } catch (e) {
      ctx.toast(e.message);
    } finally {
      busy = false;
      paint();
    }
  }

  function doCheck() {
    run(async () => {
      const answers = M.answersToCheck(model, state, solved);
      if (!answers.length) return ctx.toast('글자를 다 채운 단어가 없어요.');
      const res = await api.check(puzzleId, session, answers);
      counts = { hints: res.hintCount, wrong: res.wrongCount };
      for (const { entryId, status } of res.results) {
        if (status === 'CORRECT') {
          solved.add(entryId);
          wrongEntries.delete(entryId);
          M.lockEntry(model, state, entryId);
          showCard(entryId);
        } else if (status === 'WRONG') {
          wrongEntries.add(entryId);
        }
      }
      if (res.completed) finish('COMPLETED', res.elapsedSec);
      else if (res.results.every((r) => r.status !== 'CORRECT')) ctx.toast('틀린 단어가 있어요. 빨간 칸을 확인해 보세요.');
    });
  }

  function doHint() {
    run(async () => {
      const id = sel.entryId;
      if (solved.has(id)) return ctx.toast('이미 맞힌 단어예요.');
      const [r, c] = model.entries.get(id).coords[0];
      if (state.locked[r][c]) return ctx.toast('첫 글자가 이미 공개되어 있어요.');
      const res = await api.hint(puzzleId, session, id);
      counts.hints = res.hintCount;
      M.applyHint(model, state, id, res.letter);
      clearWrongAt(r, c);
    });
  }

  function doDefinition() {
    run(async () => {
      const id = sel.entryId;
      if (solved.has(id)) return ctx.toast('이미 맞힌 단어예요.');
      if (definitions.has(id)) return;
      const res = await api.definitionHint(puzzleId, session, id);
      counts.hints = res.hintCount;
      definitions.set(id, res.definition);
    });
  }

  function doReveal() {
    if (busy || finished) return;
    if (!confirm('정답을 보면 이 퍼즐은 포기로 끝나요. 계속할까요?')) return;
    run(async () => {
      const res = await api.reveal(puzzleId, session);
      counts = { hints: res.hintCount, wrong: res.wrongCount };
      revealedCards = [];
      for (const { entryId, card } of res.entries) {
        M.revealEntry(model, state, entryId, card.english);
        revealedCards.push({ entryId, card });
      }
      wrongEntries.clear();
      finish('GAVE_UP', res.elapsedSec);
    });
  }

  async function showCard(entryId) {
    const mode = getMeaningMode();
    if (mode === 'off') return;
    try {
      const card = await api.wordCard(model.entries.get(entryId).wordId, session);
      if (handle.cancelled) return;
      clear(cardEl).append(h('div', {},
        h('strong', {}, card.english), ' ', h('span', {}, card.korean),
        mode === 'full'
          ? h('div', { class: 'muted' }, `${card.partOfSpeech ? POS_LABEL[card.partOfSpeech] + ' · ' : ''}${card.definition ?? ''}`)
          : null));
      cardEl.hidden = false;
    } catch {
      // 카드는 부가 정보라 실패해도 풀이를 막지 않는다
    }
  }

  // ---------- 종료 ----------
  let elapsedFinal = null;
  function finish(kind, serverElapsed) {
    finished = kind;
    elapsedFinal = serverElapsed ?? null;
    clearInterval(timerId);
    const seconds = elapsedFinal ?? (Date.now() - startedAt) / 1000;
    timerEl.textContent = formatTime(seconds);
    const resultButton = h('button', { type: 'button', class: 'primary', onclick: goResult }, '결과 보기');
    clear(bannerEl).append(
      h('strong', {}, kind === 'COMPLETED' ? '🎉 퍼즐을 완성했어요!' : '정답을 공개했어요.'), ' ', resultButton);
    bannerEl.hidden = false;
    paint();
  }

  async function goResult() {
    const button = bannerEl.querySelector('button');
    button.disabled = true;
    try {
      const cards = finished === 'GAVE_UP'
        ? revealedCards
        : await Promise.all(model.order.map(async (id) => ({
          entryId: id, card: await api.wordCard(model.entries.get(id).wordId, session),
        })));
      ctx.shared.result = {
        kind: finished,
        elapsedSec: elapsedFinal ?? Math.floor((Date.now() - startedAt) / 1000),
        hintCount: counts.hints,
        wrongCount: counts.wrong,
        puzzle: { id: puzzle.id, difficulty: puzzle.difficulty, topic: puzzle.topic, size: puzzle.size },
        entries: cards.map(({ entryId, card }) => {
          const e = model.entries.get(entryId);
          return { number: e.number, direction: e.direction, card };
        }),
      };
      ctx.navigate('#/result');
    } catch (e) {
      ctx.toast(e.message);
      button.disabled = false;
    }
  }

  const timerId = setInterval(() => {
    if (!finished) timerEl.textContent = formatTime((Date.now() - startedAt) / 1000);
  }, 1000);

  handle.cleanup = () => {
    clearInterval(timerId);
    document.removeEventListener('keydown', onKeydown);
  };

  paint();
  kbd.focus({ preventScroll: true });
}
