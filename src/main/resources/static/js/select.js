import { h, clear } from './dom.js';
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
