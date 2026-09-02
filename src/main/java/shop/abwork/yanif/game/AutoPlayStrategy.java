package shop.abwork.yanif.game;

import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.DiscardPile;
import shop.abwork.yanif.game.model.Hand;
import shop.abwork.yanif.game.validator.CardCombinationValidator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides the best available move for a player who cannot act (AFK or disconnected)
 * so the game can continue when their turn timer expires.
 *
 * Heuristic, deterministic:
 * 1. Call Yaniv whenever the hand is at or below the threshold.
 * 2. Otherwise discard the legal combination that minimizes the resulting hand total,
 *    taking the best improving card from the discard pile when one exists
 *    (otherwise drawing from the deck).
 */
public final class AutoPlayStrategy {

    public enum ActionType { CALL_YANIV, DISCARD_AND_DRAW }

    public record Decision(ActionType type, List<Card> discardCards, String drawSource, String drawnCardId) {
        static Decision yaniv() {
            return new Decision(ActionType.CALL_YANIV, List.of(), null, null);
        }

        static Decision discardAndDraw(List<Card> discardCards, String drawSource, String drawnCardId) {
            return new Decision(ActionType.DISCARD_AND_DRAW, discardCards, drawSource, drawnCardId);
        }
    }

    private AutoPlayStrategy() {}

    /**
     * Decide the move for the current player.
     *
     * @param hand           current player's hand
     * @param pile           current discard pile (for drawable-card evaluation)
     * @param yanivThreshold maximum hand score for which calling Yaniv is legal
     */
    public static Decision decide(Hand hand, DiscardPile pile, int yanivThreshold) {
        if (hand.calculateScore() <= yanivThreshold) {
            return Decision.yaniv();
        }

        Candidate best = null;
        for (List<Card> combo : candidateDiscards(hand)) {
            Candidate candidate = evaluate(hand, pile, combo);
            if (best == null || candidate.resultingTotal < best.resultingTotal
                    || (candidate.resultingTotal == best.resultingTotal && beatsOnTieBreak(combo, best.combo))) {
                best = candidate;
            }
        }

        return Decision.discardAndDraw(best.combo, best.drawSource, best.drawnCardId);
    }

    private record Candidate(List<Card> combo, int resultingTotal, String drawSource, String drawnCardId) {}

    /**
     * Evaluate discarding {@code combo}: total after removal, improved by taking the best
     * drawable discard-pile card that reduces the total (swap for the worst remaining card).
     */
    private static Candidate evaluate(Hand hand, DiscardPile pile, List<Card> combo) {
        List<Card> remaining = new ArrayList<>(hand.getCards());
        for (Card c : combo) {
            // Cards are unique within a hand; remove by identity of id
            remaining.removeIf(r -> r.getId().equals(c.getId()));
        }
        int baseTotal = remaining.stream().mapToInt(Card::getValue).sum();

        String drawSource = "DECK";
        String drawnCardId = null;
        int bestTotal = baseTotal; // deck draw is treated as value-neutral

        if (!remaining.isEmpty()) {
            Card worstRemaining = remaining.stream()
                    .max(Comparator.comparingInt(Card::getValue))
                    .orElseThrow();
            for (Card drawable : pile.getDrawableCards()) {
                boolean stillInHand = remaining.stream().anyMatch(r -> r.getId().equals(drawable.getId()));
                if (!stillInHand && drawable.getValue() < worstRemaining.getValue()) {
                    int swappedTotal = baseTotal - worstRemaining.getValue() + drawable.getValue();
                    if (swappedTotal < bestTotal) {
                        bestTotal = swappedTotal;
                        drawSource = "DISCARD_PILE";
                        drawnCardId = drawable.getId();
                    }
                }
            }
        }

        return new Candidate(new ArrayList<>(combo), bestTotal, drawSource, drawnCardId);
    }

    /**
     * Deterministic tie-break: prefer discarding more cards, then lexicographically smaller ids.
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
     * (2-4 cards), same-suit sequences of length >= 2 (Ace-low and Ace-high), and
     * mixed-suit sequences only when they clear the entire hand (validator's rule).
     */
    static List<List<Card>> candidateDiscards(Hand hand) {
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

        // Mixed-suit sequence (only legal at exactly a full hand's worth of cards)
        if (cards.size() == CardCombinationValidator.FULL_HAND_SIZE
                && CardCombinationValidator.isValidSequence(cards, handSize)) {
            candidates.add(new ArrayList<>(cards));
        }

        return candidates;
    }

    /**
     * Consecutive-rank windows within one suit, checked with Ace as both low and high.
     */
    private static List<List<Card>> sequenceWindows(List<Card> suitCards) {
        List<List<Card>> windows = new ArrayList<>();
        int[] aceValues = {1, 14};
        for (int aceValue : aceValues) {
            Map<Integer, Card> bySeqRank = new HashMap<>();
            for (Card card : suitCards) {
                bySeqRank.put(seqRankValue(card.getRank(), aceValue), card);
            }
            List<Integer> ranks = new ArrayList<>(bySeqRank.keySet());
            java.util.Collections.sort(ranks);
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

    /**
     * Sequence position of a rank (2..13), with Ace mapped to the given value (1 or 14).
     * Note {@link Card#getValue()} is the scoring value (face cards = 10) and must not be used here.
     */
    private static int seqRankValue(Card.Rank rank, int aceValue) {
        if (rank == Card.Rank.ACE) {
            return aceValue;
        }
        return switch (rank) {
            case TWO -> 2;
            case THREE -> 3;
            case FOUR -> 4;
            case FIVE -> 5;
            case SIX -> 6;
            case SEVEN -> 7;
            case EIGHT -> 8;
            case NINE -> 9;
            case TEN -> 10;
            case JACK -> 11;
            case QUEEN -> 12;
            case KING -> 13;
            default -> -1;
        };
    }
}
