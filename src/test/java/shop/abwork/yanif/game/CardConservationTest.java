package shop.abwork.yanif.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.Hand;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A card lives in exactly one place: a hand, the deck, the discard pile, or the
 * pending discard. These tests pin that invariant — 52 cards in, 52 cards out —
 * against the paths that used to break it.
 */
public class CardConservationTest {

    private static final String P1 = "player-1";
    private static final String P2 = "player-2";

    private YanivGameEngine newEngine() {
        return new YanivGameEngine("game-1", List.of(P1, P2), 7, 200);
    }

    /**
     * Count every card the engine can account for. Must always be 52.
     * The pending discard is not exposed, so callers should finish the turn first.
     */
    private int totalCardsInPlay(YanivGameEngine engine) {
        int total = engine.getDeckCount();
        for (String playerId : engine.getAllPlayerIds()) {
            total += engine.getPlayerHand(playerId).size();
        }
        total += engine.getDiscardPile().getAllDiscardedCards().size();
        return total;
    }

    /**
     * Rebuild the engine with an empty deck, so the next deck draw must recycle.
     * Goes through the snapshot because the deck is otherwise not reachable.
     */
    private YanivGameEngine withEmptyDeck(YanivGameEngine engine) {
        GameSnapshot snapshot = GameSnapshot.fromJson(engine.toSnapshot());
        snapshot.deckRemaining = new ArrayList<>();
        return YanivGameEngine.fromSnapshot(snapshot.toJson());
    }

    /**
     * Draw from the deck and settle a bonus discard if the drawn card triggers one,
     * so the turn always reaches finalizeTurn. Without this the deck's random shuffle
     * decides whether the pending discard has reached the pile yet.
     */
    private void drawFromDeckAndFinishTurn(YanivGameEngine engine, String playerId) {
        engine.processDraw(playerId, "DECK", null);
        if (engine.isBonusDiscardActive()) {
            engine.processBonusDiscard(playerId, false);
        }
    }

    /**
     * Every id in the deck, duplicates included — a Set here would hide exactly the
     * defect these tests exist to catch.
     */
    private List<String> deckCardIds(YanivGameEngine engine) {
        GameSnapshot snapshot = GameSnapshot.fromJson(engine.toSnapshot());
        List<String> ids = new ArrayList<>();
        for (GameSnapshot.CardDto card : snapshot.deckRemaining) {
            ids.add(card.id);
        }
        return ids;
    }

    // ==========================================
    // Listing the same card twice must not remove it
    // ==========================================

    @Test
    @DisplayName("A discard naming the same card twice is rejected without touching the hand")
    void duplicateCardIdsAreRejectedAndHandIsUnchanged() {
        YanivGameEngine engine = newEngine();
        String current = engine.getCurrentPlayer();
        Hand hand = engine.getPlayerHand(current);

        Card card = hand.getCards().get(0);
        List<Card> sameCardTwice = List.of(card, card);

        int handSizeBefore = hand.size();

        assertThrows(IllegalArgumentException.class,
                () -> engine.processDiscard(current, sameCardTwice),
                "The same card listed twice is not a pair and must be rejected");

        assertEquals(handSizeBefore, engine.getPlayerHand(current).size(),
                "A rejected discard must leave the hand untouched");
        assertTrue(engine.getPlayerHand(current).containsCard(card),
                "The card must still be in the hand after a rejected discard");
        assertEquals(YanivGameEngine.GameState.WAIT_FOR_TURN, engine.getCurrentState(),
                "A rejected discard must not advance the turn state");
    }

    @Test
    @DisplayName("Repeated duplicate-id discards cannot drain a hand toward a Yaniv call")
    void repeatedDuplicateDiscardsCannotDrainHand() {
        YanivGameEngine engine = newEngine();
        String current = engine.getCurrentPlayer();
        int handSizeBefore = engine.getPlayerHand(current).size();

        for (Card card : engine.getPlayerHand(current).getCards()) {
            try {
                engine.processDiscard(current, List.of(card, card));
            } catch (IllegalArgumentException expected) {
                // rejected, as it should be
            }
        }

        assertEquals(handSizeBefore, engine.getPlayerHand(current).size(),
                "No card may be lost by listing it twice");
        assertEquals(52, totalCardsInPlay(engine),
                "All 52 cards must still be accounted for");
    }

    // ==========================================
    // Recycling the deck must not resurrect staged cards
    // ==========================================

    @Test
    @DisplayName("Recycling an empty deck does not duplicate the pending discard")
    void recycleDeckDoesNotDuplicatePendingDiscard() {
        YanivGameEngine engine = withEmptyDeck(newEngine());
        assertEquals(0, engine.getDeckCount(), "precondition: deck is empty");

        String current = engine.getCurrentPlayer();
        Card discarded = engine.getPlayerHand(current).getCards().get(0);

        // Discard stages the card, then the deck draw forces a recycle.
        engine.processDiscard(current, List.of(discarded));
        drawFromDeckAndFinishTurn(engine, current);

        List<String> inDeck = deckCardIds(engine);
        List<String> onPile = engine.getDiscardPile().getAllDiscardedCards()
                .stream().map(Card::getId).toList();

        assertTrue(onPile.contains(discarded.getId()),
                "precondition: the discarded card reached the pile");
        assertFalse(inDeck.contains(discarded.getId()),
                "The staged discard must not be regenerated into the recycled deck");
    }

    @Test
    @DisplayName("A deck recycle conserves all 52 cards")
    void recycleDeckConservesFiftyTwoCards() {
        YanivGameEngine engine = withEmptyDeck(newEngine());

        String current = engine.getCurrentPlayer();
        Card discarded = engine.getPlayerHand(current).getCards().get(0);

        engine.processDiscard(current, List.of(discarded));
        drawFromDeckAndFinishTurn(engine, current);

        assertEquals(52, totalCardsInPlay(engine),
                "Deck + hands + pile must total exactly 52 after a recycle");
    }

    @Test
    @DisplayName("No card id appears in both the deck and a hand after a recycle")
    void recycleDeckProducesNoDuplicateIds() {
        YanivGameEngine engine = withEmptyDeck(newEngine());

        String current = engine.getCurrentPlayer();
        engine.processDiscard(current, List.of(engine.getPlayerHand(current).getCards().get(0)));
        drawFromDeckAndFinishTurn(engine, current);

        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();

        for (String id : deckCardIds(engine)) {
            if (!seen.add(id)) duplicates.add(id);
        }
        for (String playerId : engine.getAllPlayerIds()) {
            for (Card card : engine.getPlayerHand(playerId).getCards()) {
                if (!seen.add(card.getId())) duplicates.add(card.getId());
            }
        }
        for (Card card : engine.getDiscardPile().getAllDiscardedCards()) {
            if (!seen.add(card.getId())) duplicates.add(card.getId());
        }

        assertTrue(duplicates.isEmpty(), "Duplicated card ids after recycle: " + duplicates);
    }

    // ==========================================
    // A second discard in the same turn must not strand the first
    // ==========================================

    @Test
    @DisplayName("Discarding twice in one turn is rejected instead of stranding the first discard")
    void secondDiscardInSameTurnIsRejected() {
        YanivGameEngine engine = newEngine();
        String current = engine.getCurrentPlayer();

        List<Card> hand = engine.getPlayerHand(current).getCards();
        Card first = hand.get(0);
        Card second = hand.get(1);

        engine.processDiscard(current, List.of(first));
        assertEquals(YanivGameEngine.GameState.DRAW_CARD, engine.getCurrentState(),
                "precondition: the engine is waiting for a draw");

        assertThrows(IllegalStateException.class,
                () -> engine.processDiscard(current, List.of(second)),
                "A second discard before drawing must be rejected");

        assertTrue(engine.getPlayerHand(current).containsCard(second),
                "The rejected second discard must leave its card in the hand");

        // Completing the turn normally must still conserve every card.
        drawFromDeckAndFinishTurn(engine, current);
        assertEquals(52, totalCardsInPlay(engine),
                "All 52 cards must be accounted for after the turn completes");
    }
}
