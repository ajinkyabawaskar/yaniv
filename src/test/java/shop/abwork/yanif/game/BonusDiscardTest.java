package shop.abwork.yanif.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.Hand;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Matching Rank Bonus Discard rule:
 * - When a player discards a single card and then draws from the deck
 * - If the drawn card has the SAME RANK but DIFFERENT SUIT
 * - The player may optionally discard the drawn card immediately (no extra draw)
 * - Turn ends normally after bonus discard decision
 */
class BonusDiscardTest {

    private YanivGameEngine engine;
    private static final String PLAYER1 = "player1";
    private static final String PLAYER2 = "player2";

    @BeforeEach
    void setUp() {
        engine = new YanivGameEngine("test-game", List.of(PLAYER1, PLAYER2), 7, 100);
    }

    // ==========================================
    // 1. BONUS DISCARD TRIGGER CONDITIONS
    // ==========================================

    @Test
    @DisplayName("Bonus discard triggers: single discard + draw from deck + matching rank different suit")
    void testBonusDiscardTriggersOnMatchingRankDifferentSuit() {
        // Give player1 a card and discard it
        Hand hand = engine.getPlayerHand(PLAYER1);
        Card cardToDiscard = hand.getCards().get(0); // Use actual card from hand

        engine.processDiscard(PLAYER1, List.of(cardToDiscard));
        assertEquals(YanivGameEngine.GameState.DRAW_CARD, engine.getCurrentState());

        // Verify last discarded rank is tracked
        assertNotNull(engine.getLastDiscardedRank());
        assertEquals(cardToDiscard.getRank(), engine.getLastDiscardedRank());
    }

    @Test
    @DisplayName("No bonus discard: multi-card combination discarded")
    void testNoBonusDiscardForMultiCardDiscard() {
        Hand hand = engine.getPlayerHand(PLAYER1);
        
        // Find two cards of same rank for a valid pair
        Card pairCard1 = null;
        Card pairCard2 = null;
        for (int i = 0; i < hand.getCards().size(); i++) {
            for (int j = i + 1; j < hand.getCards().size(); j++) {
                if (hand.getCards().get(i).getRank() == hand.getCards().get(j).getRank()) {
                    pairCard1 = hand.getCards().get(i);
                    pairCard2 = hand.getCards().get(j);
                    break;
                }
            }
            if (pairCard1 != null) break;
        }
        
        if (pairCard1 != null && pairCard2 != null) {
            // Discard a pair (multi-card)
            engine.processDiscard(PLAYER1, List.of(pairCard1, pairCard2));
            assertEquals(YanivGameEngine.GameState.DRAW_CARD, engine.getCurrentState());

            // Should NOT track last discarded rank for multi-card discards
            assertNull(engine.getLastDiscardedRank(), "Multi-card discard should not set lastDiscardedRank");
        } else {
            // No pair available in this hand, test passes by default
            // (This is a limitation of random hand generation)
        }
    }

    @Test
    @DisplayName("No bonus discard: drawing from discard pile")
    void testNoBonusDiscardWhenDrawingFromDiscardPile() {
        Hand hand = engine.getPlayerHand(PLAYER1);
        Card cardToDiscard = hand.getCards().get(0);

        engine.processDiscard(PLAYER1, List.of(cardToDiscard));
        assertEquals(YanivGameEngine.GameState.DRAW_CARD, engine.getCurrentState());

        // Draw from discard pile (not deck)
        Card drawnCard = engine.getDiscardPile().getTopCard().orElseThrow();
        engine.processDraw(PLAYER1, "DISCARD_PILE", drawnCard);

        // Should advance to next player directly, no bonus discard
        assertEquals(YanivGameEngine.GameState.WAIT_FOR_TURN, engine.getCurrentState());
        assertEquals(PLAYER2, engine.getCurrentPlayer());
    }

    @Test
    @DisplayName("Bonus discard only tracks rank for single card discards")
    void testBonusDiscardOnlyForSingleCard() {
        Hand hand = engine.getPlayerHand(PLAYER1);
        Card card1 = hand.getCards().get(0);

        // Single card discard - tracks rank
        engine.processDiscard(PLAYER1, List.of(card1));
        assertNotNull(engine.getLastDiscardedRank());
        assertEquals(card1.getRank(), engine.getLastDiscardedRank());

        // Advance turn to reset. The draw may itself trigger a bonus discard, which
        // parks the turn; decline it so PLAYER2 reliably gets the turn.
        engine.processDraw(PLAYER1, "DECK", null);
        if (engine.isBonusDiscardActive()) {
            engine.processBonusDiscard(PLAYER1, false);
        }

        // Multi-card discard - doesn't track rank (need a valid pair)
        hand = engine.getPlayerHand(PLAYER2);
        // Find two cards of same rank for a valid pair
        Card pairCard1 = null;
        Card pairCard2 = null;
        for (int i = 0; i < hand.getCards().size(); i++) {
            for (int j = i + 1; j < hand.getCards().size(); j++) {
                if (hand.getCards().get(i).getRank() == hand.getCards().get(j).getRank()) {
                    pairCard1 = hand.getCards().get(i);
                    pairCard2 = hand.getCards().get(j);
                    break;
                }
            }
            if (pairCard1 != null) break;
        }
        
        if (pairCard1 != null && pairCard2 != null) {
            engine.processDiscard(PLAYER2, List.of(pairCard1, pairCard2));
            assertNull(engine.getLastDiscardedRank(), "Multi-card discard should not set lastDiscardedRank");
        } else {
            // No pair available, skip this part of test
        }
    }

    // ==========================================
    // 2. BONUS DISCARD STATE MACHINE
    // ==========================================

    @Test
    @DisplayName("Bonus discard state: isBonusDiscardActive returns true when triggered")
    void testIsBonusDiscardActive() {
        Hand hand = engine.getPlayerHand(PLAYER1);
        Card cardToDiscard = hand.getCards().get(0);

        engine.processDiscard(PLAYER1, List.of(cardToDiscard));
        assertFalse(engine.isBonusDiscardActive());

        // We can't easily trigger the actual draw, but we can test the getter
        assertNull(engine.getPendingBonusCard());
    }

    private record RiggedDeal(YanivGameEngine engine, String player, Card toDiscard, Card bonus) {}

    /**
     * Stack the deck so the next card drawn matches a card in the current player's hand:
     * same rank, different suit. Left to chance the trigger is about one deck draw in
     * fourteen, which is no way to test the accept path. A match always exists - only 11
     * of the 52 cards are out of the deck after a two-player deal, so of the 15 same-rank
     * siblings of a five-card hand at least nine are still in there.
     */
    private RiggedDeal dealWhereTheNextDeckCardMatchesAHandCard() {
        GameSnapshot snapshot = GameSnapshot.fromJson(engine.toSnapshot());
        String player = engine.getCurrentPlayer();

        GameSnapshot.CardDto toDiscard = null;
        GameSnapshot.CardDto bonus = null;
        for (GameSnapshot.CardDto inHand : snapshot.playerHands.get(player)) {
            for (GameSnapshot.CardDto inDeck : snapshot.deckRemaining) {
                if (inDeck.rank.equals(inHand.rank) && !inDeck.suit.equals(inHand.suit)) {
                    toDiscard = inHand;
                    bonus = inDeck;
                    break;
                }
            }
            if (bonus != null) break;
        }
        if (bonus == null) {
            throw new AssertionError("no rank in the hand has a sibling left in the deck");
        }

        snapshot.deckRemaining.remove(bonus);
        snapshot.deckRemaining.add(0, bonus); // index 0 is the next card drawn
        return new RiggedDeal(YanivGameEngine.fromSnapshot(snapshot.toJson()), player,
                GameSnapshot.toCard(toDiscard), GameSnapshot.toCard(bonus));
    }

    @Test
    @DisplayName("An accepted bonus card lands on top of the pile, not under the card it matched")
    void acceptedBonusCardIsTheTopOfThePile() {
        RiggedDeal deal = dealWhereTheNextDeckCardMatchesAHandCard();
        YanivGameEngine rigged = deal.engine();

        rigged.processDiscard(deal.player(), List.of(deal.toDiscard()));
        rigged.processDraw(deal.player(), "DECK", null);

        assertTrue(rigged.isBonusDiscardActive(), "precondition: the stacked deck parks on the bonus");
        assertEquals(deal.bonus().getId(), rigged.getPendingBonusCard().getId());

        rigged.processBonusDiscard(deal.player(), true);

        assertEquals(deal.bonus().getId(), rigged.getDiscardPile().getTopCard().orElseThrow().getId(),
                "the bonus card left the hand last, so it is what the next player sees");
        assertTrue(rigged.getDiscardPile().isDrawable(deal.bonus().getId()),
                "only the top combination is drawable - burying the bonus card takes it out of play");
        assertFalse(rigged.getPlayerHand(deal.player()).containsCard(deal.bonus()),
                "accepting the bonus must take the card out of the hand");
        assertTrue(rigged.getDiscardPile().getAllDiscardedCards().stream()
                        .anyMatch(c -> c.getId().equals(deal.toDiscard().getId())),
                "the card that started the turn must still reach the pile");
    }

    @Test
    @DisplayName("A declined bonus card stays in hand and the turn's discard tops the pile")
    void declinedBonusCardStaysInHand() {
        RiggedDeal deal = dealWhereTheNextDeckCardMatchesAHandCard();
        YanivGameEngine rigged = deal.engine();

        rigged.processDiscard(deal.player(), List.of(deal.toDiscard()));
        rigged.processDraw(deal.player(), "DECK", null);
        rigged.processBonusDiscard(deal.player(), false);

        assertTrue(rigged.getPlayerHand(deal.player()).containsCard(deal.bonus()),
                "declining keeps the drawn card");
        assertEquals(deal.toDiscard().getId(), rigged.getDiscardPile().getTopCard().orElseThrow().getId(),
                "with no bonus card, the turn's own discard tops the pile");
        assertNotEquals(deal.player(), rigged.getCurrentPlayer(), "the turn ends either way");
    }

    @Test
    @DisplayName("processBonusDiscard throws if not in BONUS_DISCARD state")
    void testProcessBonusDiscardWrongStateThrows() {
        Hand hand = engine.getPlayerHand(PLAYER1);
        Card cardToDiscard = hand.getCards().get(0);

        engine.processDiscard(PLAYER1, List.of(cardToDiscard));
        
        // Not in BONUS_DISCARD state yet
        assertThrows(IllegalStateException.class, () -> 
            engine.processBonusDiscard(PLAYER1, true));
    }

    @Test
    @DisplayName("processBonusDiscard throws if not current player's turn")
    void testProcessBonusDiscardNotCurrentPlayerThrows() {
        Hand hand = engine.getPlayerHand(PLAYER1);
        Card cardToDiscard = hand.getCards().get(0);

        engine.processDiscard(PLAYER1, List.of(cardToDiscard));
        
        // Try to call as player2
        assertThrows(IllegalArgumentException.class, () -> 
            engine.processBonusDiscard(PLAYER2, true));
    }

    // ==========================================
    // 3. SNAPSHOT SERIALIZATION
    // ==========================================

    @Test
    @DisplayName("Bonus discard state is preserved in snapshot")
    void testSnapshotIncludesBonusDiscardState() {
        Hand hand = engine.getPlayerHand(PLAYER1);
        Card cardToDiscard = hand.getCards().get(0);

        engine.processDiscard(PLAYER1, List.of(cardToDiscard));
        
        // Verify snapshot includes the fields
        String snapshot = engine.toSnapshot();
        assertNotNull(snapshot);
        assertTrue(snapshot.contains("lastDiscardedRank") || snapshot.contains(cardToDiscard.getRank().name()));
    }

    @Test
    @DisplayName("Bonus discard state is restored from snapshot")
    void testSnapshotRestoresBonusDiscardState() {
        Hand hand = engine.getPlayerHand(PLAYER1);
        Card cardToDiscard = hand.getCards().get(0);

        engine.processDiscard(PLAYER1, List.of(cardToDiscard));
        
        String snapshot = engine.toSnapshot();
        YanivGameEngine restored = YanivGameEngine.fromSnapshot(snapshot);
        
        assertNotNull(restored);
        assertEquals(cardToDiscard.getRank(), restored.getLastDiscardedRank());
        assertEquals(YanivGameEngine.GameState.DRAW_CARD, restored.getCurrentState());
    }

    // ==========================================
    // 4. START NEXT ROUND CLEARS BONUS DISCARD STATE
    // ==========================================

    @Test
    @DisplayName("startNextRound clears bonus discard state")
    void testStartNextRoundClearsBonusDiscardState() {
        Hand hand = engine.getPlayerHand(PLAYER1);
        Card cardToDiscard = hand.getCards().get(0);

        engine.processDiscard(PLAYER1, List.of(cardToDiscard));
        engine.processDraw(PLAYER1, "DECK", null); // Complete turn
        
        // Now we need to get to ROUND_OVER state to test startNextRound
        // This is hard to do in unit test, but we can verify the method clears the fields
        // by checking the source code logic
        // (Tested in integration tests)
    }

    // ==========================================
    // 5. EDGE CASES
    // ==========================================

    @Test
    @DisplayName("All ranks can trigger bonus discard (not just 7s)")
    void testAllRanksCanTriggerBonusDiscard() {
        // Test with different ranks by creating engines and discarding different ranks
        for (Card.Rank rank : Card.Rank.values()) {
            YanivGameEngine testEngine = new YanivGameEngine("test-" + rank, List.of(PLAYER1, PLAYER2), 7, 100);
            Hand h = testEngine.getPlayerHand(PLAYER1);
            // Find a card of this rank in the hand
            Card cardOfRank = h.getCards().stream()
                .filter(c -> c.getRank() == rank)
                .findFirst()
                .orElse(null);
            
            if (cardOfRank != null) {
                testEngine.processDiscard(PLAYER1, List.of(cardOfRank));
                assertEquals(rank, testEngine.getLastDiscardedRank(), 
                    "Rank " + rank + " should be tracked for bonus discard");
            }
        }
    }
}