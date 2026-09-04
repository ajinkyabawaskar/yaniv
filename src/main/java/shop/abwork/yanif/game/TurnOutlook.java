package shop.abwork.yanif.game;

import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.DiscardPile;
import shop.abwork.yanif.game.model.Hand;
import shop.abwork.yanif.game.validator.CardCombinationValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a hand can do on its next turn: every discard the engine would accept, and the
 * hand score each line leaves the player holding once the mandatory draw is counted.
 *
 * One model, two readers. {@link AutoPlayStrategy} plays the best line for a player who
 * cannot act, and the spectator meters report how close a player is to calling Yaniv.
 * They agree by construction rather than by two implementations happening to match.
 *
 * A turn is <em>discard, then draw</em>, and the engine only ever <em>adds</em> the drawn
 * card ({@code YanivGameEngine.processDraw}). Nothing here may model it as a swap: a card
 * drawn cannot cancel out a card left behind.
 */
public final class TurnOutlook {

    /**
     * What an unknown card off the deck is worth. A 52-card deck holds 340 points --
     * four suits of (Ace..Ten = 55) + (J/Q/K = 30) -- averaging 6.54, which rounds to 7.
     *
     * Used both to price a deck draw and to decide whether a known pile card is worth
     * taking instead. Because card values are whole numbers, comparing against 7 and
     * against the exact 6.54 pick the same cards.
     */
    public static final int AVERAGE_DECK_CARD_VALUE = 7;

    /**
     * One playable turn: what goes to the pile, where the replacement comes from, and
     * the hand score left behind afterwards.
     *
     * @param drawnCardId the pile card taken, or null when drawing from the deck
     */
    public record Move(List<Card> discard, String drawSource, String drawnCardId, int resultingHandScore) {}

    private TurnOutlook() {}

    /**
     * The lowest hand score this player could be holding at the end of their next turn.
     * Combination-aware: thirty points held as three Kings is one discard from nothing,
     * while the same thirty in unrelated cards can only leave one card at a time.
     */
    public static int lowestReachableHandScore(Hand hand, DiscardPile pile) {
        return bestMove(hand, pile).resultingHandScore();
    }

    /**
     * The turn that leaves the lowest hand score, with a deterministic tie-break so the
     * same hand always produces the same move.
     */
    public static Move bestMove(Hand hand, DiscardPile pile) {
        Move best = null;
        for (List<Card> combo : legalDiscards(hand)) {
            Move move = evaluate(hand, pile, combo);
            if (best == null || move.resultingHandScore() < best.resultingHandScore()
                    || (move.resultingHandScore() == best.resultingHandScore()
                        && beatsOnTieBreak(combo, best.discard()))) {
                best = move;
            }
        }
        return best;
    }

    /**
     * Score one candidate discard: what is left in hand, plus the cheapest card the
     * player can put on top of it.
     */
    private static Move evaluate(Hand hand, DiscardPile pile, List<Card> combo) {
        List<Card> remaining = new ArrayList<>(hand.getCards());
        for (Card c : combo) {
            // Cards are unique within a hand; remove by identity of id
            remaining.removeIf(r -> r.getId().equals(c.getId()));
        }
        int keptScore = remaining.stream().mapToInt(Card::getValue).sum();

        // The draw is compulsory, so every line pays for one card. A known pile card is
        // worth taking only when it undercuts what the deck is expected to hand over.
        String drawSource = "DECK";
        String drawnCardId = null;
        int drawCost = AVERAGE_DECK_CARD_VALUE;

        for (Card drawable : pile.getDrawableCards()) {
            if (drawable.getValue() < drawCost) {
                drawCost = drawable.getValue();
                drawSource = "DISCARD_PILE";
                drawnCardId = drawable.getId();
            }
        }

        return new Move(new ArrayList<>(combo), drawSource, drawnCardId, keptScore + drawCost);
    }

    /**
     * Deterministic tie-break: prefer discarding more cards, then lexicographically
     * smaller ids.
     */
    private static boolean beatsOnTieBreak(List<Card> challenger, List<Card> incumbent) {
        if (challenger.size() != incumbent.size()) {
            return challenger.size() > incumbent.size();
        }
        return joinedIds(challenger).compareTo(joinedIds(incumbent)) < 0;
    }

    private static String joinedIds(List<Card> cards) {
        return cards.stream().map(Card::getId).sorted().reduce((a, b) -> a + "|" + b).orElse("");
    }

    /**
     * All legal combinations the player could discard: every single, every same-rank set
     * (2-4 cards), same-suit sequences of length >= 2 (Ace-low and Ace-high), and the
     * whole hand as a mixed-suit sequence, which is legal only because it clears the hand.
     */
    public static List<List<Card>> legalDiscards(Hand hand) {
        List<Card> cards = hand.getCards();
        int handSize = cards.size();
        List<List<Card>> candidates = new ArrayList<>();

        // Singles
        for (Card card : cards) {
            candidates.add(List.of(card));
        }

        // Sets grouped by rank
        Map<Card.Rank, List<Card>> byRank = new HashMap<>();
        for (Card card : cards) {
            byRank.computeIfAbsent(card.getRank(), k -> new ArrayList<>()).add(card);
        }
        for (List<Card> group : byRank.values()) {
            for (int size = Math.min(group.size(), 4); size >= 2; size--) {
                candidates.add(group.subList(0, size));
            }
        }

        // Sequences per suit (both ace-low and ace-high windows)
        Map<Card.Suit, List<Card>> bySuit = new HashMap<>();
        for (Card card : cards) {
            bySuit.computeIfAbsent(card.getSuit(), k -> new ArrayList<>()).add(card);
        }
        for (List<Card> suitCards : bySuit.values()) {
            candidates.addAll(sequenceWindows(suitCards));
        }

        // The whole hand as one mixed-suit sequence; legal only because it empties the hand
        if (CardCombinationValidator.isValidSequence(cards, handSize)) {
            candidates.add(new ArrayList<>(cards));
        }

        return candidates;
    }

    /**
     * Consecutive-rank windows within one suit, checked with Ace as both low and high.
     */
    private static List<List<Card>> sequenceWindows(List<Card> suitCards) {
        List<List<Card>> windows = new ArrayList<>();
        for (boolean aceHigh : new boolean[]{false, true}) {
            Map<Integer, Card> bySeqRank = new HashMap<>();
            for (Card card : suitCards) {
                bySeqRank.put(card.getRank().sequenceValue(aceHigh), card);
            }
            List<Integer> ranks = new ArrayList<>(bySeqRank.keySet());
            Collections.sort(ranks);
            // find runs of consecutive values
            int start = 0;
            for (int i = 1; i <= ranks.size(); i++) {
                boolean runEnds = i == ranks.size() || ranks.get(i) - ranks.get(i - 1) != 1;
                if (!runEnds) {
                    continue;
                }
                if (i - start >= 2) {
                    List<Card> run = new ArrayList<>();
                    for (int j = start; j < i; j++) {
                        run.add(bySeqRank.get(ranks.get(j)));
                    }
                    // every sub-window of length >= 2 is itself a legal sequence
                    for (int len = run.size(); len >= 2; len--) {
                        for (int from = 0; from + len <= run.size(); from++) {
                            windows.add(new ArrayList<>(run.subList(from, from + len)));
                        }
                    }
                }
                start = i;
            }
        }
        return windows;
    }
}
