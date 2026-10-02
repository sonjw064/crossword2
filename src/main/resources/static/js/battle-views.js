import { h, clear } from './dom.js';
import * as api from './api.js';
import * as M from './grid-model.js';
import {
  answerToSubmit, battleErrorText, clockOffset, countdownSeconds, medal, phaseOf, remainingSeconds, scoreboard, solvedIds,
  submitFeedback,
} from './battle-model.js';
import { DIFFICULTY_LABEL, DIRECTION_LABEL, POS_LABEL, formatTime, topicLabel } from './labels.js';

/**
 * 대련(레이스) 경기 화면. 방 화면이 만들어 두고 방송을 받을 때마다 update()로 알려 준다(DOM을 다시 만들지 않아 입력한 글자가 유지된다).
 * 정답은 서버만 알고 있으며, 이 화면은 단어를 다 채우면 서버에 제출해 맞음/틀림만 돌려받는다.
 * 힌트·정의·정답 보기·단어 카드는 없다. 단어 카드는 경기가 끝난 뒤 결과 화면에서만 받는다.
 */
export function createBattle({ code, ctx, command, isHost }) {
  const element = h('div', { class: 'battle' });
  let room = null;
  let me = null;
  let offset = 0;
  let model = null;
  let state = null;
  let puzzleId = null;
  let loading = false;
  let sel = null;
  let pendingRestore = null;
  let lastVersion = -1;
  let destroyed = false;
  let resultLoaded = false;
  const solved = new Set();
  const wrongEntries = new Set();
  const pending = new Set();
  const lastTried = new Map();

  // ---------- DOM ----------
  const bannerEl = h('div', { class: 'battle-banner', role: 'status' });
  const feedbackEl = h('p', { class: 'battle-feedback', role: 'status', hidden: true });
  const boardHolder = h('div', {});
  const scoreEl = h('ol', { class: 'scoreboard' });
  const resultEl = h('div', { class: 'battle-result', hidden: true });
  const clueBar = h('div', { class: 'clue-bar' });
  const kbd = h('input', {
    class: 'kbd', type: 'text', inputmode: 'text', autocapitalize: 'none', autocomplete: 'off',
    autocorrect: 'off', spellcheck: 'false', lang: 'en', 'aria-label': '글자 입력', tabindex: '-1',
  });
  let cellEls = [];
  let gridEl = null;
  const clueItems = new Map();

  element.append(bannerEl, h('div', { class: 'battle-layout' }, boardHolder, h('aside', {}, h('h2', {}, '점수'), scoreEl)), resultEl);

  // ---------- 시계 ----------
  const serverNow = () => Date.now() + offset;
  const timer = setInterval(drawBanner, 250);

  function drawBanner() {
    if (!room?.match) return;
    const phase = phaseOf(room, serverNow());
    if (phase === 'countdown') {
      bannerEl.textContent = `곧 시작해요! ${countdownSeconds(room.match, serverNow())}`;
    } else if (phase === 'playing') {
      bannerEl.textContent = `남은 시간 ${formatTime(remainingSeconds(room.match, serverNow()))}`;
    } else if (phase === 'timeup') {
      bannerEl.textContent = '시간이 끝났어요. 결과를 집계하는 중…';
    } else {
      bannerEl.textContent = '경기가 끝났어요!';
    }
    bannerEl.dataset.phase = phase;
  }

  const canPlay = () => model && phaseOf(room, serverNow()) === 'playing' && !finishedByMe();

  function finishedByMe() {
    const mine = room?.match?.progress.find((p) => p.playerId === me);
    return !mine || mine.finished || mine.abandoned;
  }

  // ---------- 퍼즐 구조 ----------
  async function loadPuzzle(id) {
    if (loading || model) return;
    loading = true;
    try {
      const puzzle = await api.getPuzzle(id);
      if (destroyed) return;
      model = M.buildModel(puzzle);
      state = M.createState(model);
      sel = M.selectEntry(model, model.order[0], state);
      buildBoard(puzzle);
      applyRestore();
      paint();
    } catch (e) {
      ctx.toast(e.message);
    } finally {
      loading = false;
    }
  }

  function buildBoard(puzzle) {
    cellEls = model.cells.map((row) => row.map(() => null));
    gridEl = h('div', { class: 'grid', role: 'grid', 'aria-label': '십자말풀이 판' });
    gridEl.style.setProperty('--size', model.size); // CSP: 인라인 style 속성 대신 CSSOM 사용
    for (let r = 0; r < model.size; r++) {
      for (let c = 0; c < model.size; c++) {
        const cell = model.cells[r][c];
        if (!cell) {
          gridEl.append(h('div', { class: 'cell block' }));
          continue;
        }
        const el = h('div', { class: 'cell', 'data-r': r, 'data-c': c, role: 'gridcell' },
          cell.number !== null ? h('span', { class: 'num' }, cell.number) : null, h('span', { class: 'letter' }));
        cellEls[r][c] = el;
        gridEl.append(el);
      }
    }
    gridEl.addEventListener('click', (e) => {
      const el = e.target.closest('.cell[data-r]');
      if (!el) return;
      choose(M.select(model, sel, Number(el.dataset.r), Number(el.dataset.c)));
    });
    clueItems.clear();
    const list = (direction) => {
      const ul = h('ul', { class: 'clue-list' });
      for (const id of model.order) {
        const e = model.entries.get(id);
        if (e.direction !== direction) continue;
        const item = h('li', {}, h('button', { type: 'button', class: 'clue', onclick: () => choose(M.selectEntry(model, id, state)) },
          h('span', { class: 'clue-no' }, e.number), h('span', { class: 'clue-text' }, e.clue),
          h('small', { class: 'muted' }, `${e.partOfSpeech ? POS_LABEL[e.partOfSpeech] + ' · ' : ''}${e.length}글자`)));
        clueItems.set(id, item);
        ul.append(item);
      }
      return ul;
    };
    clear(boardHolder).append(
      h('p', { class: 'muted' }, `${DIFFICULTY_LABEL[puzzle.difficulty]} · ${topicLabel(puzzle.topic)} · ${puzzle.size}×${puzzle.size}`),
      clueBar, feedbackEl,
      h('div', { class: 'play-layout' },
        h('div', { class: 'board-col' }, gridEl, kbd),
        h('div', { class: 'clues' }, h('h2', {}, '가로'), list('ACROSS'), h('h2', {}, '세로'), list('DOWN'))));
  }

  // ---------- 그리기 ----------
  function paint() {
    if (!model) return;
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
        el.classList.toggle('wrong', wrongCells.has(key) && !state.locked[r][c]);
      }
    }
    for (const [id, item] of clueItems) {
      item.classList.toggle('active', id === sel.entryId);
      item.classList.toggle('solved', solved.has(id));
    }
    const entry = model.entries.get(sel.entryId);
    clear(clueBar).append(h('b', {}, `${entry.number} ${DIRECTION_LABEL[entry.direction]}`), ' ', h('span', {}, entry.clue), ' ',
      h('small', { class: 'muted' }, `${entry.partOfSpeech ? POS_LABEL[entry.partOfSpeech] + ' · ' : ''}${entry.length}글자`));
  }

  function drawScoreboard() {
    clear(scoreEl);
    for (const row of scoreboard(room, me)) {
      const bar = h('div', { class: 'bar' }, h('span', { class: 'bar-fill' }));
      bar.firstChild.style.setProperty('width', `${row.percent}%`);
      scoreEl.append(h('li', { class: `score-row${row.you ? ' you' : ''}${row.abandoned ? ' gone' : ''}` },
        h('div', { class: 'score-head' },
          h('b', {}, row.rank !== null ? `${medal(row.rank)} ` : '', row.nickname, row.you ? ' (나)' : ''),
          h('span', { class: 'score' }, `${row.score}점`)),
        bar,
        h('small', { class: 'muted' }, `${row.solved}/${row.total} 단어`,
          row.finished ? ' · 완성!' : '', row.abandoned ? ' · 나감' : '', !row.connected && !row.abandoned && !row.left ? ' · 연결 끊김' : '')));
    }
  }

  function say(kind, text) {
    feedbackEl.textContent = text;
    feedbackEl.className = `battle-feedback ${kind}`;
    feedbackEl.hidden = !text;
  }

  // ---------- 입력 ----------
  function choose(next) {
    sel = next;
    paint();
    kbd.focus({ preventScroll: true });
  }

  function clearWrongAt(r, c) {
    const cell = model.cells[r][c];
    wrongEntries.delete(cell.across);
    wrongEntries.delete(cell.down);
  }

  /** 방금 입력한 칸이 속한 단어들 중 다 채워진 것을 제출한다. */
  function maybeSubmit(row, col) {
    const cell = model.cells[row][col];
    for (const entryId of [cell.across, cell.down]) {
      if (entryId === null) continue;
      const answer = answerToSubmit({ answer: M.answerOf(model, state, entryId), solved, entryId, pending, lastTried });
      if (!answer) continue;
      pending.add(entryId);
      lastTried.set(entryId, answer);
      if (!command('submit', { entryId, answer })) {
        pending.delete(entryId);
        lastTried.delete(entryId);
        say('bad', '연결이 끊겼어요. 다시 연결되면 한 번 더 입력해 주세요.');
      }
    }
  }

  function type(ch) {
    if (!canPlay()) return;
    const { row, col } = sel;
    clearWrongAt(row, col);
    sel = M.typeLetter(model, state, sel, ch);
    paint();
    maybeSubmit(row, col);
  }

  function erase() {
    if (!canPlay()) return;
    clearWrongAt(sel.row, sel.col);
    sel = M.backspace(model, state, sel);
    lastTried.clear(); // 고쳐 쓰면 같은 답도 다시 보낼 수 있다
    paint();
  }

  function onKeydown(e) {
    if (!model || e.ctrlKey || e.metaKey || e.altKey) return;
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

  // ---------- 서버 응답 ----------
  /** 내가 맞힌 단어(서버 기억)로 칸을 복원한다: 새로고침하거나 다시 연결했을 때. */
  function applyRestore() {
    if (!model || !pendingRestore) return;
    for (const { entryId, word } of pendingRestore) {
      const entry = model.entries.get(entryId);
      if (!entry) continue;
      entry.coords.forEach(([r, c], i) => {
        state.letters[r][c] = word[i];
      });
      M.lockEntry(model, state, entryId);
      solved.add(entryId);
    }
    pendingRestore = null;
  }

  function restore(words) {
    const known = solvedIds(words);
    const missing = (words ?? []).filter((w) => !solved.has(w.entryId));
    if (!known.size || !missing.length) return;
    pendingRestore = missing;
    applyRestore();
    paint();
  }

  function onAck(ack) {
    pending.delete(ack.entryId);
    const fb = submitFeedback(ack);
    say(fb.kind, fb.text);
    if (!model) return;
    if (ack.status === 'CORRECT' || ack.status === 'ALREADY_SOLVED') {
      solved.add(ack.entryId);
      wrongEntries.delete(ack.entryId);
      M.lockEntry(model, state, ack.entryId);
    } else if (ack.status === 'WRONG') {
      wrongEntries.add(ack.entryId);
    }
    paint();
  }

  /** 제출 중 서버가 보낸 오류(속도 제한 등). 어떤 제출인지 모르므로 대기 표시를 모두 풀어 다시 시도할 수 있게 한다. */
  function onError(code) {
    pending.clear();
    lastTried.clear();
    const text = battleErrorText(code);
    if (text) say('bad', text);
    return !!text;
  }

  // ---------- 결과 ----------
  async function loadResult() {
    if (resultLoaded) return;
    resultLoaded = true;
    try {
      const result = await api.getRoomResult(code);
      if (destroyed) return;
      clear(resultEl).append(
        h('h2', {}, '결과'),
        h('ol', { class: 'standings' }, result.standings.map((s) => h('li', { class: s.you ? 'you' : '' },
          h('b', {}, `${medal(s.rank)} ${s.nickname}${s.you ? ' (나)' : ''}`), ' ',
          h('span', {}, `${s.score}점`), ' ', h('small', { class: 'muted' }, `${s.solved}단어${s.abandoned ? ' · 나감' : ''}`)))),
        h('div', { class: 'actions' },
          isHost() ? h('button', { type: 'button', class: 'primary', onclick: () => command('rematch') }, '다시 하기')
            : h('small', { class: 'muted' }, '방장이 다시 하기를 누르면 대기실로 돌아가요.')),
        h('h2', {}, '이 퍼즐의 단어'),
        h('ul', { class: 'cards' }, result.cards.map(({ entryId, card }) => {
          const e = model?.entries.get(entryId);
          return h('li', { class: 'word-card' },
            e ? h('div', { class: 'muted' }, `${e.number} ${DIRECTION_LABEL[e.direction]}`) : null,
            h('strong', {}, card.english), ' ', h('span', {}, card.korean),
            h('div', { class: 'muted' }, `${card.partOfSpeech ? POS_LABEL[card.partOfSpeech] + ' · ' : ''}${card.definition ?? ''}`),
            card.example ? h('div', { class: 'example' }, `“${card.example}”`) : null);
        })));
      resultEl.hidden = false;
    } catch (e) {
      resultLoaded = false;
      ctx.toast(e.message);
    }
  }

  // ---------- 방송 반영 ----------
  function update(nextRoom, myId) {
    if (destroyed || !nextRoom?.match) return;
    me = myId;
    room = nextRoom;
    if (nextRoom.version !== lastVersion) {
      lastVersion = nextRoom.version;
      offset = clockOffset(nextRoom.match, Date.now());
    }
    if (nextRoom.puzzleId && puzzleId !== nextRoom.puzzleId) {
      puzzleId = nextRoom.puzzleId;
      loadPuzzle(puzzleId);
    }
    drawBanner();
    drawScoreboard();
    const phase = phaseOf(room, serverNow());
    if (phase === 'ended') {
      say('info', '');
      loadResult();
    }
    if (model && phase === 'playing') kbd.focus({ preventScroll: true });
  }

  function destroy() {
    destroyed = true;
    clearInterval(timer);
    document.removeEventListener('keydown', onKeydown);
  }

  return { element, update, onAck, onError, restore, destroy };
}
