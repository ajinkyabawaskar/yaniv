import {
  Card,
  applyHandSortMode,
  compareCardsByRank,
  compareCardsBySuit,
  detectHandSortMode,
  mergeHandPreservingSort,
} from './yanivRules';

const card = (id: string, rank: string, suit: string): Card => ({ id, rank, suit });

const ranks = (cards: Card[]): string[] => cards.map((c) => c.rank);
const ids = (cards: Card[]): string[] => cards.map((c) => c.id);

describe('hand sort comparators', () => {
  it('orders ranks Ace-low through King', () => {
    const hand = [
      card('k', 'KING', 'SPADES'),
      card('a', 'ACE', 'HEARTS'),
      card('5', 'FIVE', 'DIAMONDS'),
      card('j', 'JACK', 'CLUBS'),
    ];
    expect(ranks([...hand].sort(compareCardsByRank))).toEqual(['ACE', 'FIVE', 'JACK', 'KING']);
  });

  it('groups suits alphabetically, rank ascending within a suit', () => {
    const hand = [
      card('h5', 'FIVE', 'HEARTS'),
      card('cK', 'KING', 'CLUBS'),
      card('c2', 'TWO', 'CLUBS'),
      card('dA', 'ACE', 'DIAMONDS'),
    ];
    expect(ids([...hand].sort(compareCardsBySuit))).toEqual(['c2', 'cK', 'dA', 'h5']);
  });
});

describe('detectHandSortMode', () => {
  it('reports rank for a rank-ordered hand', () => {
    expect(
      detectHandSortMode([card('a', 'ACE', 'SPADES'), card('5', 'FIVE', 'HEARTS'), card('k', 'KING', 'CLUBS')])
    ).toBe('rank');
  });

  it('reports suit for a suit-grouped hand that is not rank-ordered', () => {
    expect(
      detectHandSortMode([
        card('c2', 'TWO', 'CLUBS'),
        card('c5', 'FIVE', 'CLUBS'),
        card('hA', 'ACE', 'HEARTS'),
      ])
    ).toBe('suit');
  });

  it('reports null for a custom order', () => {
    expect(
      detectHandSortMode([card('k', 'KING', 'SPADES'), card('2', 'TWO', 'HEARTS'), card('5', 'FIVE', 'CLUBS')])
    ).toBeNull();
  });
});

describe('mergeHandPreservingSort', () => {
  it('keeps a rank-sorted hand sorted when a card is drawn', () => {
    const displayed = [card('2', 'TWO', 'HEARTS'), card('5', 'FIVE', 'DIAMONDS'), card('k', 'KING', 'CLUBS')];
    const incoming = [card('2', 'TWO', 'HEARTS'), card('5', 'FIVE', 'DIAMONDS'), card('7', 'SEVEN', 'SPADES')];
    expect(ranks(mergeHandPreservingSort(displayed, incoming, [], 'rank'))).toEqual(['TWO', 'FIVE', 'SEVEN']);
  });

  it('keeps a suit-sorted hand grouped when a card is drawn', () => {
    const displayed = [card('c5', 'FIVE', 'CLUBS'), card('c2', 'TWO', 'CLUBS'), card('hA', 'ACE', 'HEARTS')];
    const sorted = applyHandSortMode(displayed, 'suit');
    const incoming = [...sorted, card('c9', 'NINE', 'CLUBS')];
    expect(ids(mergeHandPreservingSort(sorted, incoming, [], 'suit'))).toEqual(['c2', 'c5', 'c9', 'hA']);
  });

  it('detects a currently-sorted hand with no explicit mode', () => {
    const displayed = [card('a', 'ACE', 'SPADES'), card('5', 'FIVE', 'HEARTS'), card('k', 'KING', 'CLUBS')];
    const incoming = [card('a', 'ACE', 'SPADES'), card('k', 'KING', 'CLUBS'), card('3', 'THREE', 'DIAMONDS')];
    expect(ranks(mergeHandPreservingSort(displayed, incoming, [], null))).toEqual(['ACE', 'THREE', 'KING']);
  });

  it('appends fresh cards for a custom order', () => {
    const displayed = [card('k', 'KING', 'SPADES'), card('2', 'TWO', 'HEARTS')];
    const incoming = [...displayed, card('5', 'FIVE', 'CLUBS')];
    expect(ids(mergeHandPreservingSort(displayed, incoming, [], null))).toEqual(['k', '2', '5']);
  });

  it('withholds cards still flying in', () => {
    const displayed = [card('a', 'ACE', 'SPADES'), card('k', 'KING', 'CLUBS')];
    const incoming = [...displayed, card('3', 'THREE', 'DIAMONDS')];
    expect(ids(mergeHandPreservingSort(displayed, incoming, ['3'], 'rank'))).toEqual(['a', 'k']);
  });

  it('drops discarded cards and does not mutate its inputs', () => {
    const displayed = [card('2', 'TWO', 'HEARTS'), card('9', 'NINE', 'DIAMONDS'), card('k', 'KING', 'CLUBS')];
    const incoming = [card('2', 'TWO', 'HEARTS'), card('k', 'KING', 'CLUBS'), card('3', 'THREE', 'SPADES')];
    const result = mergeHandPreservingSort(displayed, incoming, [], 'rank');
    expect(ids(result)).toEqual(['2', '3', 'k']);
    expect(ids(displayed)).toEqual(['2', '9', 'k']);
  });
});
