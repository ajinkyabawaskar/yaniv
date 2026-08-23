package shop.abwork.yanif.game;

import org.junit.jupiter.api.Test;
import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.Hand;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for engine state persistence: toSnapshot/fromSnapshot must
 * preserve the exact game state (hands, deck order, discard pile, scores, turn).
 */
class YanivGameEngineSnapshotTest {

    private static String cardString(Card card) {
        return card.getId() + ":" + card.getSuit() + ":" + card.getRank();
    }

    @Test
    void freshEngineRoundTrips() {
        YanivGameEngine original = new YanivGameEngine("room-1", List.of("p1", "p2", "p3"), 7, 200);

        YanivGameEngine restored = YanivGameEngine.fromSnapshot(original.toSnapshot());

        assertNotNull(restored);
        assertEquals(original.getRoundNumber(), restored.getRoundNumber());
        assertEquals(original.getCurrentPlayer(), restored.getCurrentPlayer());
        assertEquals(original.getCurrentState(), restored.getCurrentState());
        assertEquals(original.getDeckCount(), restored.getDeckCount());
        assertEquals(original.getPlayerScores(), restored.getPlayerScores());
        assertEquals(original.getEliminatedPlayers(), restored.getEliminatedPlayers());

        // Hands identical (same cards, same order)
        for (String playerId : List.of("p1", "p2", "p3")) {
            List<String> originalHand = original.getPlayerHand(playerId).getCards()
                    .stream().map(YanivGameEngineSnapshotTest::cardString).toList();
            List<String> restoredHand = restored.getPlayerHand(playerId).getCards()
                    .stream().map(YanivGameEngineSnapshotTest::cardString).toList();
            assertEquals(originalHand, restoredHand, "hand mismatch for " + playerId);
        }
    }

    @Test
    void midGameRoundTripPreservesDiscardPileAndPendingState() {
        YanivGameEngine original = new YanivGameEngine("room-1", List.of("p1", "p2"), 7, 200);

        // p1 discards a single and draws from deck -> advances turn to p2
        Hand p1 = original.getPlayerHand("p1");
        Card discard = p1.getCards().stream()
                .filter(c -> c.getValue() >= 10)
                .findFirst()
                .orElse(p1.getCards().get(0));
        original.processDiscard("p1", List.of(discard));
        original.processDraw("p1", "DECK", null);

        YanivGameEngine restored = YanivGameEngine.fromSnapshot(original.toSnapshot());

        assertEquals(original.getCurrentPlayer(), restored.getCurrentPlayer());
        assertEquals(original.getDeckCount(), restored.getDeckCount());
        assertEquals(original.getDiscardPile().getDiscardCount(),
                restored.getDiscardPile().getDiscardCount());
        // Top combination type and drawability preserved
        assertEquals(
                original.getDiscardPile().getTopCombination().get().getType(),
                restored.getDiscardPile().getTopCombination().get().getType());
        assertEquals(
                original.getDiscardPile().getDrawableCards().stream().map(Card::getId).toList(),
                restored.getDiscardPile().getDrawableCards().stream().map(Card::getId).toList());
    }

    @Test
    void deckOrderReplaysIdenticallyAfterRestore() {
        YanivGameEngine original = new YanivGameEngine("room-1", List.of("p1", "p2"), 7, 200);

        // Play a full turn on the original: discard worst card, draw from deck
        playWorstCardTurn(original, original.getCurrentPlayer());
        String nextPlayerAfterOriginalTurn = original.getCurrentPlayer();

        YanivGameEngine restored = YanivGameEngine.fromSnapshot(original.toSnapshot());

        // Both engines should now deal identical future draws for the same sequence of turns
        for (int i = 0; i < 6; i++) {
            String player = restored.getCurrentPlayer();
            assertEquals(player, original.getCurrentPlayer());

            playWorstCardTurn(original, player);
            playWorstCardTurn(restored, player);

            assertEquals(original.getDeckCount(), restored.getDeckCount());
            for (String p : List.of("p1", "p2")) {
                assertEquals(
                        original.getPlayerHand(p).calculateScore(),
                        restored.getPlayerHand(p).calculateScore(),
                        "hand score diverged at turn " + i + " for " + p);
                assertEquals(
                        original.getPlayerHand(p).getCards().stream().map(Card::getId).sorted().toList(),
                        restored.getPlayerHand(p).getCards().stream().map(Card::getId).sorted().toList(),
                        "hand contents diverged at turn " + i + " for " + p);
            }
        }
        assertTrue(original.getRoundNumber() >= 1);
        assertNotNull(nextPlayerAfterOriginalTurn);
    }

    @Test
    void yanivCallAndRoundOverStatesRoundTrip() {
        // Build a controlled engine via reflection-free path: play until someone can call yaniv is
        // slow; instead restore directly into YANIV_CALLED / ROUND_OVER using snapshots of a real
        // engine driven there by low hands. Simpler: verify callYaniv path round-trips.
        YanivGameEngine original = new YanivGameEngine("room-1", List.of("p1", "p2"), 200, 300);
        // threshold 200 makes any hand a legal yaniv call
        original.callYaniv("p1");

        YanivGameEngine restored = YanivGameEngine.fromSnapshot(original.toSnapshot());
        assertEquals(YanivGameEngine.GameState.YANIV_CALLED, restored.getCurrentState());
        assertEquals("p1", restored.getCallerId());

        restored.resolveYanivCall();
        original.resolveYanivCall();

        assertEquals(original.isAsaf(), restored.isAsaf());
        assertEquals(original.getAsafByUserId(), restored.getAsafByUserId());
        assertEquals(original.getRoundScores(), restored.getRoundScores());
        assertEquals(original.getRoundWinners(), restored.getRoundWinners());
        assertEquals(original.getPlayerScores(), restored.getPlayerScores());
        assertEquals(original.getCurrentState(), restored.getCurrentState());
    }

    @Test
    void corruptAndMissingSnapshotsReturnNull() {
        assertNull(YanivGameEngine.fromSnapshot(null));
        assertNull(YanivGameEngine.fromSnapshot(""));
        assertNull(YanivGameEngine.fromSnapshot("not json at all"));
        assertNull(YanivGameEngine.fromSnapshot("{\"version\":999}"));
    }

    /**
     * Play one deterministic turn for the given player: discard their highest-value card,
     * draw from deck. Mirrors what AutoPlayStrategy does in its simplest form.
     */
    private void playWorstCardTurn(YanivGameEngine engine, String playerId) {
        if (!engine.getCurrentPlayer().equals(playerId)) {
            throw new IllegalStateException("Not " + playerId + "'s turn");
        }
        if (engine.isRoundOver()) {
            engine.startNextRound();
        }
        Hand hand = engine.getPlayerHand(playerId);
        Card worst = hand.getCards().stream()
                .max(java.util.Comparator.comparingInt(Card::getValue))
                .orElseThrow();
        engine.processDiscard(playerId, List.of(worst));
        engine.processDraw(playerId, "DECK", null);
    }
}
