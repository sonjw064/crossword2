import test from 'node:test';
import assert from 'node:assert/strict';
import * as R from '../../main/resources/static/js/review-model.js';

const items = ['a', 'b', 'c', 'd'].map((english, i) => ({ wordId: i + 1, english }));

/** 항상 같은 수열을 내는 가짜 난수. */
function sequence(values) {
  let i = 0;
  return () => values[i++ % values.length];
}

test('shuffle: 원소는 그대로이고 원본은 바뀌지 않는다', () => {
  const original = [...items];
  const result = R.shuffle(items, sequence([0.9, 0.1, 0.5]));
  assert.deepEqual(items, original);
  assert.deepEqual([...result].sort((x, y) => x.wordId - y.wordId), items);
});

test('shuffle: 난수가 같으면 결과도 같다', () => {
  assert.deepEqual(R.shuffle(items, sequence([0.3, 0.7, 0.2])), R.shuffle(items, sequence([0.3, 0.7, 0.2])));
});

test('shuffle: 빈 목록과 한 장도 안전하다', () => {
  assert.deepEqual(R.shuffle([]), []);
  assert.deepEqual(R.shuffle([items[0]]), [items[0]]);
});

test('createDeck: 처음에는 첫 카드의 앞면', () => {
  const deck = R.createDeck(items);
  assert.equal(R.current(deck).english, 'a');
  assert.equal(deck.revealed, false);
  assert.deepEqual(R.position(deck), { number: 1, total: 4 });
});

test('createDeck: 섞기 옵션', () => {
  const deck = R.createDeck(items, { shuffled: true, random: sequence([0, 0, 0]) });
  assert.equal(deck.cards.length, 4);
  assert.notDeepEqual(deck.cards.map((c) => c.english), ['a', 'b', 'c', 'd']);
});

test('reveal/flip: 뒤집기와 되돌리기', () => {
  let deck = R.createDeck(items);
  deck = R.reveal(deck);
  assert.equal(deck.revealed, true);
  deck = R.flip(deck);
  assert.equal(deck.revealed, false);
  deck = R.flip(deck);
  assert.equal(deck.revealed, true);
});

test('next/prev: 이동하면 항상 앞면, 양 끝에서는 멈춘다', () => {
  let deck = R.reveal(R.createDeck(items));
  deck = R.next(deck);
  assert.equal(R.current(deck).english, 'b');
  assert.equal(deck.revealed, false);
  deck = R.next(R.next(R.next(deck)));
  assert.equal(R.current(deck).english, 'd');
  assert.equal(R.isLast(deck), true);
  assert.equal(R.next(deck), deck);
  deck = R.prev(R.prev(R.prev(R.prev(deck))));
  assert.equal(R.current(deck).english, 'a');
  assert.equal(R.isFirst(deck), true);
  assert.equal(R.prev(deck), deck);
});

test('reshuffle: 처음 카드의 앞면부터 다시 시작한다', () => {
  let deck = R.next(R.next(R.createDeck(items)));
  deck = R.reveal(deck);
  const again = R.reshuffle(deck, sequence([0.5, 0.2, 0.8]));
  assert.equal(again.index, 0);
  assert.equal(again.revealed, false);
  assert.equal(again.cards.length, 4);
});

test('빈 덱: 안전하게 아무 일도 하지 않는다', () => {
  const deck = R.createDeck([]);
  assert.equal(R.current(deck), null);
  assert.equal(R.reveal(deck), deck);
  assert.equal(R.flip(deck), deck);
  assert.equal(R.next(deck), deck);
  assert.deepEqual(R.position(deck), { number: 0, total: 0 });
});
