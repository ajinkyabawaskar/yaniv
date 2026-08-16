package shop.abwork.yanif.game.model;

import java.util.*;

/**
 * Hand represents a player's collection of cards.
 */
public class Hand {

    private List<Card> cards;

    public Hand() {
        this.cards = new ArrayList<>();
    }

    public Hand(Collection<Card> cards) {
        this.cards = new ArrayList<>(cards);
    }

    /**
     * Add a card to the hand.
     */
    public void addCard(Card card) {
        cards.add(card);
    }

    /**
     * Remove a card from the hand.
     */
    public boolean removeCard(Card card) {
        return cards.remove(card);
    }

    /**
     * Get all cards in hand.
     */
    public List<Card> getCards() {
        return new ArrayList<>(cards);
    }

    /**
     * Get number of cards in hand.
     */
    public int size() {
        return cards.size();
    }

    /**
     * Check if hand is empty.
     */
    public boolean isEmpty() {
        return cards.isEmpty();
    }

    /**
     * Calculate the total score (sum of card values).
     * Jokers count as 0, face cards (J, Q, K) count as 10.
     */
    public int calculateScore() {
        return cards.stream()
                .mapToInt(Card::getValue)
                .sum();
    }

    /**
     * Get card by ID.
     */
    public Optional<Card> getCardById(String cardId) {
        return cards.stream()
                .filter(c -> c.getId().equals(cardId))
                .findFirst();
    }

    /**
     * Check if hand contains a specific card.
     */
    public boolean containsCard(Card card) {
        return cards.contains(card);
    }

    /**
     * Clear hand.
     */
    public void clear() {
        cards.clear();
    }

    @Override
    public String toString() {
        return "Hand{" + cards.size() + " cards, score=" + calculateScore() + "}";
    }
}
