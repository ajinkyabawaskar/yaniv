package shop.abwork.yanif.game.model;

import java.util.*;

/**
 * DiscardPile represents the pile of discarded card combinations.
 * Tracks the history of discards and allows drawing from outer cards.
 */
public class DiscardPile {

    private List<DiscardCombination> discards; // Stack of discard combinations with type metadata

    public DiscardPile() {
        this.discards = new ArrayList<>();
    }

    /**
     * Add a new combination to the discard pile.
     */
    public void addCombination(List<Card> combination, DiscardCombination.Type type, int handSizeAtDiscard) {
        if (combination == null || combination.isEmpty()) {
            throw new IllegalArgumentException("Combination cannot be empty");
        }
        discards.add(new DiscardCombination(combination, type, handSizeAtDiscard));
    }

    /**
     * Add a new combination to the discard pile (backward compatible).
     * @deprecated Use addCombination(combination, type, handSizeAtDiscard) instead.
     */
    @Deprecated
    public void addCombination(List<Card> combination) {
        addCombination(combination, DiscardCombination.Type.SINGLE, -1);
    }

    /**
     * Get the top (most recent) combination.
     */
    public Optional<DiscardCombination> getTopCombination() {
        if (discards.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(discards.get(discards.size() - 1));
    }

    /**
     * Get the drawable cards from the top combination based on Yaniv rules:
     * - SINGLE: The single card
     * - SET: Any card from the set (all cards are drawable)
     * - SEQUENCE: Only the outer cards (first and last)
     * - MIXED_SEQUENCE: Only the outer cards (first and last)
     */
    public List<Card> getDrawableCards() {
        if (discards.isEmpty()) {
            return Collections.emptyList();
        }

        DiscardCombination topCombination = discards.get(discards.size() - 1);
        List<Card> cards = topCombination.getCards();
        DiscardCombination.Type type = topCombination.getType();

        switch (type) {
            case SINGLE:
                // Single card can be drawn
                return new ArrayList<>(cards);
            case SET:
                // Any card from a set can be drawn
                return new ArrayList<>(cards);
            case SEQUENCE:
            case MIXED_SEQUENCE:
                // For sequences, only outer cards (first and last) are drawable
                List<Card> drawable = new ArrayList<>();
                drawable.add(cards.get(0));  // First card
                drawable.add(cards.get(cards.size() - 1)); // Last card
                return drawable;
            default:
                return Collections.emptyList();
        }
    }

    /**
     * Get a specific drawable card from the top combination.
     */
    public Optional<Card> getDrawableCard(String cardId) {
        return getDrawableCards().stream()
                .filter(c -> c.getId().equals(cardId))
                .findFirst();
    }

    /**
     * Check if a card is drawable from the current discard pile.
     */
    public boolean isDrawable(String cardId) {
        return getDrawableCard(cardId).isPresent();
    }

    /**
     * Get the top card of the discard pile (first card of top combination).
     */
    public Optional<Card> getTopCard() {
        if (discards.isEmpty()) {
            return Optional.empty();
        }
        DiscardCombination topCombination = discards.get(discards.size() - 1);
        return Optional.of(topCombination.getCards().get(0));
    }

    /**
     * Get number of combinations in discard pile.
     */
    public int getDiscardCount() {
        return discards.size();
    }

    /**
     * Check if discard pile is empty.
     */
    public boolean isEmpty() {
        return discards.isEmpty();
    }

    /**
     * Clear the discard pile.
     */
    public void clear() {
        discards.clear();
    }

    /**
     * Get all combinations in discard order (oldest first).
     * Used for state persistence.
     */
    public List<DiscardCombination> getCombinations() {
        return new ArrayList<>(discards);
    }

    /**
     * Get all discarded cards (flattened).
     */
    public List<Card> getAllDiscardedCards() {
        return discards.stream()
                .flatMap(dc -> dc.getCards().stream())
                .toList();
    }

    @Override
    public String toString() {
        return "DiscardPile{combinations=" + discards.size() + "}";
    }
}
