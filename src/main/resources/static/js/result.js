import { h } from './dom.js';
import { DIFFICULTY_LABEL, DIRECTION_LABEL, POS_LABEL, formatTime, topicLabel } from './labels.js';

export function mountResult(root, ctx) {
  const r = ctx.shared.result;
  const done = r.kind === 'COMPLETED';

  const stat = (label, value) => h('div', { class: 'stat' }, h('span', { class: 'muted' }, label), h('b', {}, value));

  root.append(
    h('section', { class: 'result' },
      h('h1', {}, done ? '🎉 완성!' : '정답을 확인했어요'),
      h('p', { class: 'muted' },
        `${DIFFICULTY_LABEL[r.puzzle.difficulty]} · ${topicLabel(r.puzzle.topic)} · ${r.puzzle.size}×${r.puzzle.size}`),
      h('div', { class: 'stats-row' },
        stat('걸린 시간', formatTime(r.elapsedSec)), stat('힌트', `${r.hintCount}번`), stat('오답', `${r.wrongCount}번`)),
      h('div', { class: 'actions' },
        h('a', { class: 'button primary', href: `#/play/${r.puzzle.id}` }, '다시 풀기'),
        h('a', { class: 'button', href: '#/' }, '다른 퍼즐')),
      h('h2', {}, '이 퍼즐의 단어'),
      h('ul', { class: 'cards' },
        r.entries.map(({ number, direction, card }) =>
          h('li', { class: 'word-card' },
            h('div', { class: 'muted' }, `${number} ${DIRECTION_LABEL[direction]}`),
            h('strong', {}, card.english), ' ', h('span', {}, card.korean),
            h('div', { class: 'muted' },
              `${card.partOfSpeech ? POS_LABEL[card.partOfSpeech] + ' · ' : ''}${card.definition ?? ''}`),
            card.example ? h('div', { class: 'example' }, `“${card.example}”`) : null)))),
  );
}
