package shop.abwork.yanif.game.validator;

import shop.abwork.yanif.game.model.Card;

import java.util.*;

/**
 * Validates card combinations (single, set, sequence) per Yaniv rules.
 * No Jokers in this version.
 */
public class CardCombinationValidator {

    /**
     * Validate if cards form a valid single card.
     * Any single card is valid.
     */
    public static boolean isValidSingle(List<Card> cards) {
        return cards != null && cards.size() == 1;
    }

    /**
     * Validate if cards form a valid set.
     * A set requires 2 to 4 cards of the same rank.
     */
    public static boolean isValidSet(List<Card> cards) {
        if (cards == null || cards.size() < 2 || cards.size() > 4) {
            return false;
        }

        // All cards must have the same rank
        Card.Rank firstRank = cards.get(0).getRank();

        return cards.stream()
                .allMatch(c -> c.getRank() == firstRank);
    }

    /**
     * Validate if cards form a valid sequence.
     * A sequence requires 2 or more consecutive cards of the same suit.
     * Supports both Ace-low (A-2-3) and Ace-high (10-J-Q-K-A) sequences.
     * 
     * Special rule: A mixed-suit sequence is valid ONLY if it empties the entire hand
     * (i.e., discard.length == handSize).
     */
    public static boolean isValidSequence(List<Card> cards, int handSize) {
        if (cards == null || cards.size() < 2) {
            return false;
        }

        // Check for corner-wrapping (K-A-2) which is illegal
        if (hasCornerWrapping(cards)) {
            return false;
        }

        // All cards must have the same suit (standard sequence)
        Set<Card.Suit> suits = new HashSet<>();
        for (Card card : cards) {
            suits.add(card.getSuit());
        }

        boolean isStandardSequence = suits.size() <= 1;
        boolean isMixedSuitSequence = suits.size() > 1;

        // Mixed-suit sequence is only valid if it clears the entire hand
        if (isMixedSuitSequence && cards.size() != handSize) {
            return false;
        }

        // Check if sequence is valid (both Ace-low and Ace-high)
        return isValidSequenceOrder(cards);
    }

    /**
     * Validate if cards form a valid sequence (backward compatible - assumes not hand-clearing).
     * @deprecated Use isValidSequence(cards, handSize) instead.
     */
    @Deprecated
    public static boolean isValidSequence(List<Card> cards) {
        return isValidSequence(cards, -1); // -1 means not hand-clearing
    }

    /**
     * Check for corner-wrapping sequences (K-A-2) which are illegal.
     * Aces can be low (1) or high (14), but cannot bridge between Kings and 2s.
     */
    private static boolean hasCornerWrapping(List<Card> cards) {
        // Get all ranks
        Set<Card.Rank> ranks = new HashSet<>();
        for (Card card : cards) {
            ranks.add(card.getRank());
        }

        // Check if we have KING, ACE, and TWO all in the same combination
        return ranks.contains(Card.Rank.KING) 
            && ranks.contains(Card.Rank.ACE) 
            && ranks.contains(Card.Rank.TWO);
    }

    /**
     * Check if cards form a valid consecutive sequence.
     * Supports both Ace-low (A=1) and Ace-high (A=14) sequences.
     */
    private static boolean isValidSequenceOrder(List<Card> cards) {
        // Get rank values of cards
        List<Integer> ranksLow = new ArrayList<>();
        List<Integer> ranksHigh = new ArrayList<>();

        for (Card card : cards) {
            ranksLow.add(getRankValueLow(card.getRank()));
            ranksHigh.add(getRankValueHigh(card.getRank()));
        }

        // Check both Ace-low and Ace-high sequences
        return isValidSequenceRanks(ranksLow) || isValidSequenceRanks(ranksHigh);
    }

    /**
     * Check if ranks form a valid consecutive sequence.
     */
    private static boolean isValidSequenceRanks(List<Integer> ranks) {
        // Sort ranks to check for consecutiveness
        Collections.sort(ranks);

        // Verify cards are consecutive (no gaps, no duplicates)
        for (int i = 0; i < ranks.size() - 1; i++) {
            int gap = ranks.get(i + 1) - ranks.get(i) - 1;
            if (gap != 0) {
                return false; // Not consecutive or duplicate rank
            }
        }

        return true;
    }

    /**
     * Get numeric value of a rank for sequence validation (Ace-low: A=1).
     */
    private static int getRankValueLow(Card.Rank rank) {
        return switch (rank) {
            case ACE -> 1;
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
            default -> -1; // Invalid for sequence
        };
    }

    /**
     * Get numeric value of a rank for sequence validation (Ace-high: A=14).
     */
    private static int getRankValueHigh(Card.Rank rank) {
        return switch (rank) {
            case ACE -> 14;
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
            default -> -1; // Invalid for sequence
        };
    }

    /**
     * Validate if a combination is valid (single, set, or sequence).
     * Requires handSize for mixed-suit sequence validation.
     */
    public static boolean isValidCombination(List<Card> cards, int handSize) {
        return isValidSingle(cards) || isValidSet(cards) || isValidSequence(cards, handSize);
    }

    /**
     * Validate if a combination is valid (backward compatible).
     * @deprecated Use isValidCombination(cards, handSize) instead.
     */
    @Deprecated
    public static boolean isValidCombination(List<Card> cards) {
        return isValidCombination(cards, -1);
    }

    /**
     * Get the type of valid combination.
     * Requires handSize for mixed-suit sequence detection.
     */
    public static String getCombinationType(List<Card> cards, int handSize) {
        if (isValidSingle(cards)) {
            return "SINGLE";
        } else if (isValidSet(cards)) {
            return "SET";
        } else if (isValidSequence(cards, handSize)) {
            // Check if it's a mixed-suit sequence
            Set<Card.Suit> suits = new HashSet<>();
            for (Card card : cards) {
                suits.add(card.getSuit());
            }
            if (suits.size() > 1) {
                return "MIXED_SEQUENCE";
            }
            return "SEQUENCE";
        }
        return "INVALID";
    }

    /**
     * Get the type of valid combination (backward compatible).
     * @deprecated Use getCombinationType(cards, handSize) instead.
     */
    @Deprecated
    public static String getCombinationType(List<Card> cards) {
        return getCombinationType(cards, -1);
    }
}
