package shop.abwork.yanif.game.model;

import java.util.*;

/**
 * Deck represents the playing deck (52 cards, no Jokers).
 */
public class Deck {

    private List<Card> cards;
    private Random random;

    public Deck() {
        this.cards = new ArrayList<>();
        this.random = new Random();
        initializeDeck();
    }

    /**
     * Create a deck with a specific remaining card order (no shuffle).
     * Used to restore a persisted game state; index 0 is the next card drawn.
     */
    public Deck(List<Card> remainingCards) {
        this.cards = new ArrayList<>(remainingCards);
        this.random = new Random();
    }

    /**
     * Initialize a standard 52-card deck (no Jokers).
     */
    private void initializeDeck() {
        Card.Suit[] suits = {Card.Suit.HEARTS, Card.Suit.DIAMONDS, Card.Suit.CLUBS, Card.Suit.SPADES};
        Card.Rank[] ranks = {Card.Rank.ACE, Card.Rank.TWO, Card.Rank.THREE, Card.Rank.FOUR,
                Card.Rank.FIVE, Card.Rank.SIX, Card.Rank.SEVEN, Card.Rank.EIGHT,
                Card.Rank.NINE, Card.Rank.TEN, Card.Rank.JACK, Card.Rank.QUEEN, Card.Rank.KING};

        int cardId = 1;
        for (Card.Suit suit : suits) {
            for (Card.Rank rank : ranks) {
                cards.add(new Card("card_" + cardId++, suit, rank));
            }
        }
    }

    /**
     * Shuffle the deck.
     */
    public void shuffle() {
        Collections.shuffle(cards, random);
    }

    /**
     * Draw (remove and return) the top card from the deck.
     */
    public Card drawCard() {
        if (cards.isEmpty()) {
            throw new IllegalStateException("Deck is empty");
        }
        return cards.remove(0);
    }

    /**
     * Draw multiple cards.
     */
    public List<Card> drawCards(int count) {
        List<Card> drawn = new ArrayList<>();
        for (int i = 0; i < count && !cards.isEmpty(); i++) {
            drawn.add(drawCard());
        }
        return drawn;
    }

    /**
     * Get number of cards remaining in deck.
     */
    public int getRemainingCount() {
        return cards.size();
    }

    /**
     * Get remaining cards in draw order (index 0 is the next card drawn).
     * Used for state persistence.
     */
    public List<Card> getRemainingCards() {
        return new ArrayList<>(cards);
    }

    /**
     * Check if deck is empty.
     */
    public boolean isEmpty() {
        return cards.isEmpty();
    }

    /**
     * Peek at the top card without removing it.
     */
    public Optional<Card> peekTopCard() {
        return cards.isEmpty() ? Optional.empty() : Optional.of(cards.get(0));
    }

    @Override
    public String toString() {
        return "Deck{remaining=" + cards.size() + "}";
    }
}
