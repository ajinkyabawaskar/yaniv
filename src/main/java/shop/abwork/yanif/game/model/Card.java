package shop.abwork.yanif.game.model;

import java.util.Objects;

/**
 * Card domain model representing a single playing card.
 */
public class Card {

    public enum Suit {
        HEARTS, DIAMONDS, CLUBS, SPADES
    }

    /**
     * A rank carries two different ladders, and confusing them is the easiest mistake
     * to make in this codebase:
     *
     * <ul>
     *   <li>{@code value} — what the card is worth when SCORING a hand. J/Q/K are all 10.</li>
     *   <li>{@code sequenceLow}/{@code sequenceHigh} — the card's position when checking
     *       SEQUENCE adjacency. J/Q/K are 11/12/13, and Ace is 1 or 14.</li>
     * </ul>
     *
     * Score with {@code value} and J-Q-K looks like a run of three identical numbers;
     * order a run by {@code value} and it sorts wrongly. Both ladders live here so
     * there is one definition of each.
     */
    public enum Rank {
        //   scoring, seqLow, seqHigh
        ACE(1, 1, 14),
        TWO(2, 2, 2),
        THREE(3, 3, 3),
        FOUR(4, 4, 4),
        FIVE(5, 5, 5),
        SIX(6, 6, 6),
        SEVEN(7, 7, 7),
        EIGHT(8, 8, 8),
        NINE(9, 9, 9),
        TEN(10, 10, 10),
        JACK(10, 11, 11),
        QUEEN(10, 12, 12),
        KING(10, 13, 13);

        /** Value when scoring a hand. */
        public final int value;
        /** Position in a sequence with Ace low (A-2-3). */
        public final int sequenceLow;
        /** Position in a sequence with Ace high (Q-K-A). */
        public final int sequenceHigh;

        Rank(int value, int sequenceLow, int sequenceHigh) {
            this.value = value;
            this.sequenceLow = sequenceLow;
            this.sequenceHigh = sequenceHigh;
        }

        /** Position in a sequence, with the Ace treated as high or low. */
        public int sequenceValue(boolean aceHigh) {
            return aceHigh ? sequenceHigh : sequenceLow;
        }
    }

    private String id;
    private Suit suit;
    private Rank rank;

    public Card(String id, Suit suit, Rank rank) {
        this.id = id;
        this.suit = suit;
        this.rank = rank;
    }

    public String getId() {
        return id;
    }

    public Suit getSuit() {
        return suit;
    }

    public Rank getRank() {
        return rank;
    }

    public int getValue() {
        return rank.value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Card card = (Card) o;
        return Objects.equals(id, card.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return rank + " of " + suit;
    }
}
