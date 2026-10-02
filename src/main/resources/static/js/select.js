import { h, clear } from './dom.js';
import * as auth from './auth.js';
import { nicknameError } from './auth-views.js';
import * as api from './api.js';
import { DIFFICULTY_LABEL, topicLabel } from './labels.js';
import { MEANING_MODES, getMeaningMode, setMeaningMode } from './settings.js';

export async function mountSelect(root, ctx, handle) {
  const filter = { difficulty: '', topic: '', size: '' };
  let options;
  try {
    options = await api.getOptions();
  } catch (e) {
    if (handle.cancelled) return;
    root.append(h('p', { class: 'notice' }, e.message));
    return;
  }
  if (handle.cancelled) return;

  // 로그인(게스트 포함)했다면 이미 푼 퍼즐에 ✓ 표시를 한다 (실패해도 선택 화면은 그대로 쓸 수 있다)
  let completed = new Set();
  if (auth.currentUser()) {
    try {
      completed = new Set((await api.getProgress({ page: 0, pageSize: 1 })).summary.completedPuzzleIds);
    } catch {
      // 부가 정보라 무시한다
    }
    if (handle.cancelled) return;
  }

  const listEl = h('div', { class: 'puzzle-list' });
  const countEl = h('p', { class: 'muted' });
  let puzzles = [];

  function chips(name, values, labelOf) {
    const wrap = h('div', { class: 'chips', role: 'group', 'aria-label': name });
    const all = [['', '전체'], ...values.map((v) => [String(v), labelOf(v)])];
    for (const [value, label] of all) {
      const button = h('button', {
        type: 'button',
        class: 'chip',
        'aria-pressed': String(filter[key(name)] === value),
        onclick: () => {
          filter[key(name)] = value;
          wrap.querySelectorAll('.chip').forEach((el, i) => el.setAttribute('aria-pressed', String(all[i][0] === value)));
          load();
        },
      }, label);
      wrap.append(button);
    }
    return wrap;
  }
  const key = (name) => ({ 난이도: 'difficulty', 크기: 'size' })[name];

  const topicSelect = h('select', {
    id: 'topic',
    onchange: (e) => {
      filter.topic = e.target.value;
      load();
    },
  }, h('option', { value: '' }, '전체 주제'), options.topics.map((t) => h('option', { value: t }, topicLabel(t))));

  async function load() {
    try {
      const page = await api.listPuzzles(filter);
      if (handle.cancelled) return;
      puzzles = page.items;
      render();
    } catch (e) {
      ctx.toast(e.message);
    }
  }

  function render() {
    countEl.textContent = puzzles.length ? `퍼즐 ${puzzles.length}개` : '조건에 맞는 퍼즐이 없어요.';
    clear(listEl);
    for (const p of puzzles) {
      listEl.append(
        h('a', { class: 'puzzle-card', href: `#/play/${p.id}` },
          h('span', { class: `badge ${p.difficulty.toLowerCase()}` }, DIFFICULTY_LABEL[p.difficulty]),
          completed.has(p.id) ? h('span', { class: 'done', title: '푼 퍼즐' }, '✓ 완료') : null,
          h('strong', {}, topicLabel(p.topic)),
          h('span', { class: 'muted' }, `${p.size}×${p.size} · ${p.wordCount}단어`)),
      );
    }
  }

  const randomButton = h('button', {
    type: 'button',
    class: 'primary',
    onclick: () => {
      if (!puzzles.length) return ctx.toast('조건에 맞는 퍼즐이 없어요.');
      const pick = puzzles[Math.floor(Math.random() * puzzles.length)];
      ctx.navigate(`#/play/${pick.id}`);
    },
  }, '랜덤으로 시작');

  const mode = getMeaningMode();
  const settings = h('fieldset', { class: 'settings' },
    h('legend', {}, '단어 뜻 표시'),
    MEANING_MODES.map((m) =>
      h('label', { class: 'radio' },
        h('input', {
          type: 'radio', name: 'meaning', value: m.value, checked: m.value === mode,
          onchange: () => setMeaningMode(m.value),
        }),
        h('span', {}, h('b', {}, m.label), ' ', h('small', { class: 'muted' }, m.hint)))),
  );

  root.append(
    h('section', { class: 'select' },
      guestBanner(ctx),
      h('h1', {}, '퍼즐 고르기'),
      h('div', { class: 'filters' },
        h('div', { class: 'field' }, h('span', { class: 'label' }, '난이도'), chips('난이도', options.difficulties, (d) => DIFFICULTY_LABEL[d])),
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'topic' }, '주제'), topicSelect),
        h('div', { class: 'field' }, h('span', { class: 'label' }, '크기'), chips('크기', options.sizes, (s) => `${s}×${s}`)),
        randomButton),
      countEl,
      listEl,
      settings),
  );
  await load();
}

/** 로그인하지 않은 방문자에게 보이는 안내: 닉네임을 정하면 게스트로 시작해 기록을 남길 수 있다(선택). */
function guestBanner(ctx) {
  if (auth.currentUser()) return null;
  const input = h('input', { type: 'text', maxlength: '20', placeholder: '닉네임 (2~20자)', 'aria-label': '닉네임' });
  const error = h('p', { class: 'form-error', role: 'alert', hidden: true });
  const start = h('button', {
    type: 'button',
    class: 'primary',
    onclick: async () => {
      const problem = nicknameError(input.value.trim());
      error.hidden = !problem;
      error.textContent = problem;
      if (problem) return;
      start.disabled = true;
      try {
        await auth.startGuest(input.value.trim());
        ctx.navigate('#/');
      } catch (e) {
        error.textContent = e.message;
        error.hidden = false;
        start.disabled = false;
      }
    },
  }, '게스트로 시작');
  return h('div', { class: 'guest-banner' },
    h('strong', {}, '기록을 남기고 싶다면'),
    h('p', { class: 'muted' }, '닉네임만 정하면 게스트로 시작할 수 있어요. 로그인 없이 그냥 풀어도 괜찮아요.'),
    h('div', { class: 'row' }, input, start),
    error,
    h('p', { class: 'muted' }, h('a', { href: '#/login' }, '로그인'), ' · ', h('a', { href: '#/signup' }, '회원가입')));
}
