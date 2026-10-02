// 오답노트 복습 모드의 순수 로직(화면과 무관). 카드를 순서대로 넘기며 한국어 뜻을 보고 영어를 떠올린 뒤 뒤집어 확인한다.
// 이미 공개된 단어만 다루므로 채점 없이 클라이언트에서만 동작한다. (node --test 로 테스트: src/test/js)

/** Fisher-Yates 섞기. 원본은 바꾸지 않는다. */
export function shuffle(items, random = Math.random) {
  const copy = [...items];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}

/** @returns {{cards: object[], index: number, revealed: boolean}} */
export function createDeck(items, { shuffled = false, random = Math.random } = {}) {
  return { cards: shuffled ? shuffle(items, random) : [...items], index: 0, revealed: false };
}

export const current = (deck) => deck.cards[deck.index] ?? null;

export function reveal(deck) {
  return current(deck) ? { ...deck, revealed: true } : deck;
}

export function flip(deck) {
  return current(deck) ? { ...deck, revealed: !deck.revealed } : deck;
}

/** 다음 카드로(맨 끝이면 그대로). 새 카드는 항상 앞면부터 보여준다. */
export function next(deck) {
  return deck.index < deck.cards.length - 1 ? { ...deck, index: deck.index + 1, revealed: false } : deck;
}

export function prev(deck) {
  return deck.index > 0 ? { ...deck, index: deck.index - 1, revealed: false } : deck;
}

/** 처음부터 다시 섞는다. */
export function reshuffle(deck, random = Math.random) {
  return { cards: shuffle(deck.cards, random), index: 0, revealed: false };
}

export const position = (deck) => ({ number: deck.cards.length ? deck.index + 1 : 0, total: deck.cards.length });

export const isFirst = (deck) => deck.index === 0;
export const isLast = (deck) => deck.index >= deck.cards.length - 1;
