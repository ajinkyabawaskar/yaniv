/**
 * Client-side copy of the Yaniv discard rules.
 *
 * This exists so the UI can grey out an illegal selection without a server round
 * trip. It is NOT authoritative — `CardCombinationValidator.java` is, and the server
 * revalidates everything. Keep the two in step: `shared/rules-contract.json` holds a
 * shared case table that both implementations are tested against, so drift fails CI.
 *
 * Deliberately free of React, CSS and animation imports so it can be unit tested
 * directly.
 */

export interface Card {
  id: string;
  rank: string;
  suit: string;
}

/**
 * Rank value for SEQUENCE adjacency with Ace low.
 *
 * Distinct from the scoring value on purpose: J/Q/K are 11/12/13 here but all score
 * 10. Never use `calculateHandScore`'s values for sequence checks, or J-Q-K looks
 * like a run of three identical values.
 */
export const getRankValueLow = (rank: string): number => {
  const values: Record<string, number> = {
    ACE: 1, TWO: 2, THREE: 3, FOUR: 4, FIVE: 5,
    SIX: 6, SEVEN: 7, EIGHT: 8, NINE: 9, TEN: 10,
    JACK: 11, QUEEN: 12, KING: 13,
  };
  return values[rank] || 0;
};

/** Rank value for SEQUENCE adjacency with Ace high (Q-K-A). */
export const getRankValueHigh = (rank: string): number => {
  const values: Record<string, number> = {
    TWO: 2, THREE: 3, FOUR: 4, FIVE: 5,
    SIX: 6, SEVEN: 7, EIGHT: 8, NINE: 9, TEN: 10,
    JACK: 11, QUEEN: 12, KING: 13, ACE: 14,
  };
  return values[rank] || 0;
};

/** Hand score: Ace 1, pip cards face value, J/Q/K all 10. There are no Jokers. */
export const calculateHandScore = (hand: Card[]): number => {
  const rankValues: Record<string, number> = {
    ACE: 1, TWO: 2, THREE: 3, FOUR: 4, FIVE: 5,
    SIX: 6, SEVEN: 7, EIGHT: 8, NINE: 9, TEN: 10,
    JACK: 10, QUEEN: 10, KING: 10,
  };
  return hand.reduce((sum, card) => sum + (rankValues[card.rank] !== undefined ? rankValues[card.rank] : 0), 0);
};

const isValidSequenceRanks = (ranks: number[]): boolean => {
  if (ranks.length === 0) return false;
  const sorted = [...ranks].sort((a, b) => a - b);
  for (let i = 0; i < sorted.length - 1; i++) {
    if (sorted[i + 1] - sorted[i] !== 1) {
      return false;
    }
  }
  return true;
};

/**
 * Is this a legal discard? Mirrors `CardCombinationValidator.isValidCombination`.
 *
 * @param handSize size of the hand before the discard. A mixed-suit run is legal only
 *                 when it empties the hand, so leaving this out rejects every one.
 */
export const isValidCombination = (cards: Card[], handSize?: number): { valid: boolean; reason?: string } => {
  if (!cards || cards.length === 0) return { valid: false, reason: 'No cards selected' };
  if (cards.length === 1) return { valid: true };

  // The same card listed twice is not a pair.
  const uniqueIds = new Set(cards.map((c) => c.id));
  if (uniqueIds.size !== cards.length) {
    return { valid: false, reason: 'A card cannot be selected more than once' };
  }

  const ranks = cards.map((c) => c.rank);

  // Set: same rank, 2-4 cards
  if (new Set(ranks).size === 1) {
    if (cards.length > 4) {
      return { valid: false, reason: 'Sets cannot have more than 4 cards' };
    }
    return { valid: true };
  }

  // Sequence: consecutive ranks, normally one suit
  if (cards.length >= 2) {
    const suits = new Set(cards.map((c) => c.suit));
    const isMixedSuit = suits.size > 1;

    // A mixed-suit sequence is only legal when discarding it empties the hand.
    if (isMixedSuit && cards.length !== handSize) {
      return {
        valid: false,
        reason: 'A mixed-suit run is only legal when it empties your hand',
      };
    }

    const hasKing = cards.some((c) => c.rank === 'KING');
    const hasAce = cards.some((c) => c.rank === 'ACE');
    const hasTwo = cards.some((c) => c.rank === 'TWO');
    if (hasKing && hasAce && hasTwo) {
      return { valid: false, reason: 'Corner-wrapping sequences (K-A-2) are strictly illegal' };
    }

    const ranksLow = cards.map((c) => getRankValueLow(c.rank)).filter((v) => v > 0);
    const validLow = ranksLow.length === cards.length && isValidSequenceRanks(ranksLow);

    const ranksHigh = cards.map((c) => getRankValueHigh(c.rank)).filter((v) => v > 0);
    const validHigh = ranksHigh.length === cards.length && isValidSequenceRanks(ranksHigh);

    if (validLow || validHigh) {
      return { valid: true };
    }
  }

  return {
    valid: false,
    reason: 'Invalid combination. Must be: single card, set (same rank, 2–4), or same-suit sequence (2+ consecutive).',
  };
};
