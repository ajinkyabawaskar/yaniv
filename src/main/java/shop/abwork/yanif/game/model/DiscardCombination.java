package shop.abwork.yanif.game.model;

import java.util.*;

/**
 * Represents a single discard combination with its type metadata.
 * This allows the discard pile to know what type of combination was discarded
 * (SINGLE, SET, SEQUENCE, MIXED_SEQUENCE) for proper pickup rules.
 */
public class DiscardCombination {

    public enum Type {
        SINGLE, SET, SEQUENCE, MIXED_SEQUENCE
    }

    private List<Card> cards;
    private Type type;
    private int handSizeAtDiscard; // For mixed-sequence validation

    public DiscardCombination(List<Card> cards, Type type, int handSizeAtDiscard) {
        if (type == Type.SEQUENCE || type == Type.MIXED_SEQUENCE) {
            this.cards = sortSequenceCards(cards);
        } else {
            this.cards = new ArrayList<>(cards);
        }
        this.type = type;
        this.handSizeAtDiscard = handSizeAtDiscard;
    }

    private List<Card> sortSequenceCards(List<Card> inputCards) {
        if (inputCards == null || inputCards.size() <= 1) {
            return inputCards != null ? new ArrayList<>(inputCards) : new ArrayList<>();
        }

        boolean hasAce = inputCards.stream().anyMatch(c -> c.getRank() == Card.Rank.ACE);
        boolean hasKingOrQueen = inputCards.stream().anyMatch(c -> c.getRank() == Card.Rank.KING || c.getRank() == Card.Rank.QUEEN);
        boolean isAceHigh = hasAce && hasKingOrQueen;

        List<Card> sorted = new ArrayList<>(inputCards);
        sorted.sort(Comparator.comparingInt(c -> getSequenceRankValue(c.getRank(), isAceHigh)));
        return sorted;
    }

    private static int getSequenceRankValue(Card.Rank rank, boolean isAceHigh) {
        if (rank == Card.Rank.ACE) return isAceHigh ? 14 : 1;
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
            default -> 0;
        };
    }

    public List<Card> getCards() {
        return new ArrayList<>(cards);
    }

    public Type getType() {
        return type;
    }

    public int getHandSizeAtDiscard() {
        return handSizeAtDiscard;
    }

    public int size() {
        return cards.size();
    }

    public Card get(int index) {
        return cards.get(index);
    }

    @Override
    public String toString() {
        return "DiscardCombination{type=" + type + ", cards=" + cards.size() + "}";
    }
}