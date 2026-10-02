import { h, clear } from './dom.js';
import * as api from './api.js';
import * as auth from './auth.js';
import * as R from './review-model.js';
import { DIFFICULTY_LABEL, POS_LABEL, STATUS_LABEL, formatDateTime, formatTime, topicLabel } from './labels.js';

const PAGE_SIZE = 20;
const REVIEW_MAX_ITEMS = 200;

function needLogin(root) {
  root.append(h('section', {},
    h('h1', {}, '로그인이 필요해요'),
    h('p', { class: 'muted' }, '닉네임만 정해 게스트로 시작해도 기록이 남아요.'),
    h('p', {}, h('a', { class: 'button primary', href: '#/' }, '시작하기'), ' ',
      h('a', { class: 'button', href: '#/login' }, '로그인'))));
}

const stat = (label, value) =>
  h('div', { class: 'stat' }, h('span', { class: 'muted' }, label), h('b', {}, value));

/** "더 보기"로 다음 페이지를 이어 붙이는 목록 공통 처리. */
function pagedList({ container, loadPage, renderItem, emptyText }) {
  const list = h('div', { class: 'item-list' });
  const more = h('button', { type: 'button' }, '더 보기');
  let page = 0;
  let loaded = 0;

  async function loadMore() {
    more.disabled = true;
    const result = await loadPage(page);
    result.items.forEach((item) => list.append(renderItem(item)));
    loaded += result.items.length;
    page += 1;
    more.hidden = loaded >= result.total;
    more.disabled = false;
    if (loaded === 0) list.append(h('p', { class: 'muted' }, emptyText));
  }
  more.addEventListener('click', () => loadMore().catch(() => (more.disabled = false)));
  container.append(list, more);
  return loadMore();
}

// ---------------- 내 기록 ----------------

export async function mountProgress(root, ctx, handle) {
  if (!auth.currentUser()) return needLogin(root);
  let first;
  try {
    first = await api.getProgress({ page: 0, pageSize: PAGE_SIZE });
  } catch (e) {
    if (!handle.cancelled) root.append(h('p', { class: 'notice' }, e.message));
    return;
  }
  if (handle.cancelled) return;

  const s = first.summary;
  const section = h('section', { class: 'progress' },
    h('h1', {}, '내 기록'),
    h('div', { class: 'stats-row' },
      stat('완료', `${s.completed}판`), stat('포기', `${s.gaveUp}판`),
      stat('평균 시간', s.averageSeconds == null ? '-' : formatTime(s.averageSeconds)),
      stat('최고 기록', s.bestSeconds == null ? '-' : formatTime(s.bestSeconds))),
    h('h2', {}, '풀이 내역'));
  root.append(section);

  const renderRecord = (r) => h('a', { class: 'record', href: `#/play/${r.puzzleId}` },
    h('span', { class: `badge ${r.status === 'COMPLETED' ? 'easy' : 'hard'}` }, STATUS_LABEL[r.status]),
    h('strong', {}, r.difficulty ? `${DIFFICULTY_LABEL[r.difficulty]} · ${topicLabel(r.topic)} · ${r.size}×${r.size}` : `퍼즐 ${r.puzzleId}`),
    h('span', { class: 'muted' }, `${formatTime(r.elapsedSec)} · 힌트 ${r.hintCount} · 오답 ${r.wrongCount}`),
    h('span', { class: 'muted' }, formatDateTime(r.completedAt)));

  let cached = first;
  await pagedList({
    container: section,
    renderItem: renderRecord,
    emptyText: '아직 끝낸 풀이가 없어요. 퍼즐을 풀어 보세요!',
    loadPage: async (page) => {
      const res = page === 0 && cached ? cached : await api.getProgress({ page, pageSize: PAGE_SIZE });
      cached = null;
      return { items: res.records, total: res.totalRecords };
    },
  });
}

// ---------------- 오답노트 ----------------

export async function mountWrongAnswers(root, ctx, handle) {
  if (!auth.currentUser()) return needLogin(root);

  const state = { sort: 'recent' };
  const body = h('div', {});
  root.append(h('section', { class: 'notebook' }, h('h1', {}, '오답노트'),
    h('p', { class: 'muted' }, '틀렸거나 힌트를 썼거나 끝까지 못 맞힌 단어예요. 끝난 풀이의 단어만 모여요.'),
    body));

  async function render() {
    clear(body);
    let first;
    try {
      first = await api.getWrongAnswers({ page: 0, pageSize: PAGE_SIZE, sort: state.sort });
    } catch (e) {
      body.append(h('p', { class: 'notice' }, e.message));
      return;
    }
    if (handle.cancelled) return;

    const sortChips = h('div', { class: 'chips', role: 'group', 'aria-label': '정렬' },
      [['recent', '최근 순'], ['count', '많이 틀린 순']].map(([value, label]) =>
        h('button', {
          type: 'button', class: 'chip', 'aria-pressed': String(state.sort === value),
          onclick: () => {
            state.sort = value;
            render();
          },
        }, label)));
    const review = h('button', {
      type: 'button', class: 'primary', disabled: first.totalItems === 0,
      onclick: () => startReview(),
    }, `복습 시작 (${Math.min(first.totalItems, REVIEW_MAX_ITEMS)}개)`);
    body.append(h('div', { class: 'row' }, sortChips, review));

    let cached = first;
    await pagedList({
      container: body,
      renderItem: wordCard,
      emptyText: '아직 오답노트가 비어 있어요. 퍼즐을 끝내면 틀린 단어가 여기에 모여요.',
      loadPage: async (page) => {
        const res = page === 0 && cached ? cached : await api.getWrongAnswers({ page, pageSize: PAGE_SIZE, sort: state.sort });
        cached = null;
        return { items: res.items, total: res.totalItems };
      },
    });
  }

  /** 노트 전체(최대 REVIEW_MAX_ITEMS)를 불러와 복습 덱을 만든다. */
  async function startReview() {
    clear(body).append(h('p', { class: 'muted' }, '복습할 단어를 불러오는 중…'));
    const items = [];
    try {
      for (let page = 0; items.length < REVIEW_MAX_ITEMS; page++) {
        const res = await api.getWrongAnswers({ page, pageSize: 50, sort: state.sort });
        items.push(...res.items);
        if (items.length >= res.totalItems || res.items.length === 0) break;
      }
    } catch (e) {
      ctx.toast(e.message);
      return render();
    }
    if (handle.cancelled) return;
    runReview(items.slice(0, REVIEW_MAX_ITEMS));
  }

  function runReview(items) {
    let deck = R.createDeck(items, { shuffled: true });
    const card = h('div', { class: 'review-card', role: 'button', tabindex: '0', 'aria-label': '카드 뒤집기', onclick: () => update(R.flip(deck)) });
    const counter = h('p', { class: 'muted' });
    const prevBtn = h('button', { type: 'button', onclick: () => update(R.prev(deck)) }, '← 이전');
    const nextBtn = h('button', { type: 'button', class: 'primary', onclick: () => update(R.next(deck)) }, '다음 →');
    const flipBtn = h('button', { type: 'button', onclick: () => update(R.flip(deck)) }, '뒤집기');
    const shuffleBtn = h('button', { type: 'button', onclick: () => update(R.reshuffle(deck)) }, '섞기');
    const exitBtn = h('button', { type: 'button', onclick: () => render() }, '목록으로');

    function update(next) {
      deck = next;
      const w = R.current(deck);
      const pos = R.position(deck);
      counter.textContent = `${pos.number} / ${pos.total}`;
      clear(card);
      if (w) {
        card.append(
          h('div', { class: 'review-front' }, h('strong', { class: 'review-meaning' }, w.korean),
            h('span', { class: 'muted' }, `${w.partOfSpeech ? POS_LABEL[w.partOfSpeech] + ' · ' : ''}${w.english.length}글자 · 틀린 횟수 ${w.count}`)),
          deck.revealed
            ? h('div', { class: 'review-back' }, h('strong', { class: 'review-english' }, w.english),
              h('div', { class: 'muted' }, w.definition ?? ''),
              w.example ? h('div', { class: 'example' }, `“${w.example}”`) : null)
            : h('div', { class: 'muted review-hint' }, '눌러서 영어 단어 확인'));
      }
      prevBtn.disabled = R.isFirst(deck);
      nextBtn.disabled = R.isLast(deck);
    }

    function onKeydown(e) {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (e.key === 'ArrowRight') update(R.next(deck));
      else if (e.key === 'ArrowLeft') update(R.prev(deck));
      else if (e.key === ' ' || e.key === 'Enter') {
        if (e.target.tagName === 'BUTTON') return; // 버튼의 기본 동작 유지
        e.preventDefault();
        update(R.flip(deck));
      } else return;
      if (e.key.startsWith('Arrow')) e.preventDefault();
    }
    document.addEventListener('keydown', onKeydown);
    const previousCleanup = handle.cleanup;
    handle.cleanup = () => {
      document.removeEventListener('keydown', onKeydown);
      previousCleanup?.();
    };

    clear(body).append(
      h('div', { class: 'review' }, counter, card,
        h('div', { class: 'actions' }, prevBtn, flipBtn, nextBtn, shuffleBtn, exitBtn),
        h('p', { class: 'muted' }, '← → 로 이동, 스페이스/Enter로 뒤집기')));
    update(deck);
  }

  await render();
}

function wordCard(w) {
  return h('div', { class: 'word-card' },
    h('div', {}, h('strong', {}, w.english), ' ', h('span', {}, w.korean), ' ',
      h('span', { class: 'badge' }, `${w.count}번`)),
    h('div', { class: 'muted' }, `${w.partOfSpeech ? POS_LABEL[w.partOfSpeech] + ' · ' : ''}${w.definition ?? ''}`),
    w.example ? h('div', { class: 'example' }, `“${w.example}”`) : null);
}
