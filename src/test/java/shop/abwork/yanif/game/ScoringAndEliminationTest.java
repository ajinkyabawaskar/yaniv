package shop.abwork.yanif.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import shop.abwork.yanif.game.model.Card;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round scoring, the halving rule, and the finishing order used for placement.
 */
public class ScoringAndEliminationTest {

    private static final String P1 = "player-a";
    private static final String P2 = "player-b";
    private static final String P3 = "player-c";

    /** An engine with exact hands and running scores, via the snapshot restore path. */
    private YanivGameEngine craft(List<String> seats,
                                  Map<String, List<GameSnapshot.CardDto>> hands,
                                  Map<String, Integer> scores,
                                  int targetScore) {
        YanivGameEngine seed = new YanivGameEngine("room-1", seats, 7, targetScore);
        GameSnapshot snap = GameSnapshot.fromJson(seed.toSnapshot());
        snap.playerHands = new HashMap<>(hands);
        snap.playerScores = new HashMap<>(scores);
        snap.targetScore = targetScore;
        snap.currentPlayerIndex = 0;
        snap.currentState = YanivGameEngine.GameState.WAIT_FOR_TURN.name();
        return YanivGameEngine.fromSnapshot(snap.toJson());
    }

    private List<GameSnapshot.CardDto> hand(String id, String rank) {
        List<GameSnapshot.CardDto> cards = new ArrayList<>();
        cards.add(new GameSnapshot.CardDto(id, "HEARTS", rank));
        return cards;
    }

    // ==========================================
    // Halving
    // ==========================================

    @Test
    @DisplayName("A round that lands a player exactly on a multiple of 50 halves their score")
    void halvesOnLanding() {
        // P2 calls Yaniv holding 1; P1 holds 5 and takes it, 95 + 5 = 100 -> 50.
        YanivGameEngine engine = craft(List.of(P2, P1),
                Map.of(P2, hand("card_1", "ACE"), P1, hand("card_5", "FIVE")),
                Map.of(P2, 0, P1, 95),
                200);

        engine.callYaniv(P2);
        engine.contestYaniv(P1);

        assertEquals(5, engine.getRoundScores().get(P1), "precondition: P1 takes their hand");
        assertEquals(50, engine.getPlayerScores().get(P1), "95 + 5 = 100, halved to 50");
    }

    @Test
    @DisplayName("A player already on a multiple of 50 who scores 0 is NOT halved again")
    void doesNotHalveAnUnchangedScore() {
        // P1 calls Yaniv and wins the round, so their round score is 0.
        YanivGameEngine engine = craft(List.of(P1, P2),
                Map.of(P1, hand("card_1", "ACE"), P2, hand("card_5", "FIVE")),
                Map.of(P1, 50, P2, 0),
                200);

        engine.callYaniv(P1);
        engine.contestYaniv(P2);

        assertEquals(0, engine.getRoundScores().get(P1), "precondition: the winner scores 0");
        assertEquals(50, engine.getPlayerScores().get(P1),
                "a score the round did not move must not be halved again");
    }

    @Test
    @DisplayName("Halving still applies on a later landing")
    void halvesAgainOnANewLanding() {
        // P1 at 45 takes 5 -> 50 -> 25.
        YanivGameEngine engine = craft(List.of(P2, P1),
                Map.of(P2, hand("card_1", "ACE"), P1, hand("card_5", "FIVE")),
                Map.of(P2, 0, P1, 45),
                200);

        engine.callYaniv(P2);
        engine.contestYaniv(P1);

        assertEquals(25, engine.getPlayerScores().get(P1), "45 + 5 = 50, halved to 25");
    }

    // ==========================================
    // Yaniv may only be called at the start of a turn
    // ==========================================

    @Test
    @DisplayName("Yaniv cannot be called after discarding, which would hide the staged cards")
    void cannotCallYanivAfterDiscarding() {
        YanivGameEngine engine = new YanivGameEngine("room-2", List.of(P1, P2), 200, 200);
        String current = engine.getCurrentPlayer();
        Card first = engine.getPlayerHand(current).getCards().get(0);

        engine.processDiscard(current, List.of(first));

        assertThrows(IllegalStateException.class, () -> engine.callYaniv(current),
                "calling Yaniv mid-turn would score a hand missing the staged cards");
    }

    @Test
    @DisplayName("Yaniv cannot be re-sent to reset the contest window")
    void cannotReCallYanivToResetTheWindow() {
        YanivGameEngine engine = craft(List.of(P1, P2),
                Map.of(P1, hand("card_1", "ACE"), P2, hand("card_5", "FIVE")),
                Map.of(P1, 0, P2, 0),
                200);

        engine.callYaniv(P1);
        assertThrows(IllegalStateException.class, () -> engine.callYaniv(P1),
                "a second call would restart the contest timer indefinitely");
    }

    // ==========================================
    // Finishing order drives placement
    // ==========================================

    @Test
    @DisplayName("A player knocked out this round still shows the hand they were holding")
    void theKnockedOutHandIsStillOnTheResultScreen() {
        // P1 calls Yaniv on 1. P2 takes their 5 onto 96 and crosses the target; P3
        // survives on 2, so two players are left and the round -- not the game -- ends.
        YanivGameEngine engine = craft(List.of(P1, P2, P3),
                Map.of(P1, hand("card_1", "ACE"),
                       P2, hand("card_5", "FIVE"),
                       P3, hand("card_2", "TWO")),
                Map.of(P1, 0, P2, 96, P3, 0),
                100);

        engine.callYaniv(P1);
        engine.contestYaniv(P3);

        assertTrue(engine.isRoundOver(), "precondition: the round ended, the game did not");
        assertTrue(engine.getEliminatedPlayers().contains(P2), "precondition: 96 + 5 is out");
        assertEquals(List.of("card_5"),
                engine.getAllPlayerHands().getOrDefault(P2, List.of()).stream().map(Card::getId).toList(),
                "the hand that knocked them out is the whole story of the round they lost");
    }

    @Test
    @DisplayName("Continuing to the next round is what clears a knocked-out player's hand")
    void theKnockedOutHandIsClearedOnContinue() {
        YanivGameEngine engine = craft(List.of(P1, P2, P3),
                Map.of(P1, hand("card_1", "ACE"),
                       P2, hand("card_5", "FIVE"),
                       P3, hand("card_2", "TWO")),
                Map.of(P1, 0, P2, 96, P3, 0),
                100);
        engine.callYaniv(P1);
        engine.contestYaniv(P3);
        assertFalse(engine.getPlayerHand(P2).getCards().isEmpty(), "precondition: still holding it");

        engine.startNextRound();

        assertTrue(engine.getPlayerHand(P2).getCards().isEmpty(),
                "a player who is out is never dealt to again, so a kept hand would sit "
                        + "there forever as a phantom card count");
    }

    @Test
    @DisplayName("Finishing order is the winner, then players in reverse elimination order")
    void finishingOrderIsWinnerThenReverseElimination() {
        YanivGameEngine seed = new YanivGameEngine("room-3", List.of(P1, P2, P3), 7, 100);
        GameSnapshot snap = GameSnapshot.fromJson(seed.toSnapshot());
        // P3 knocked out first, then P2; P1 survives.
        snap.eliminatedPlayers = new LinkedHashSet<>(List.of(P3, P2));
        snap.winnerId = P1;
        snap.currentState = YanivGameEngine.GameState.GAME_OVER.name();
        YanivGameEngine engine = YanivGameEngine.fromSnapshot(snap.toJson());

        assertEquals(List.of(P1, P2, P3), engine.getFinishingOrder(),
                "the last player knocked out places above the first");
    }

    @Test
    @DisplayName("Elimination order survives a snapshot round trip")
    void eliminationOrderSurvivesSnapshot() {
        YanivGameEngine seed = new YanivGameEngine("room-4", List.of(P1, P2, P3), 7, 100);
        GameSnapshot snap = GameSnapshot.fromJson(seed.toSnapshot());
        snap.eliminatedPlayers = new LinkedHashSet<>(List.of(P3, P2));
        snap.winnerId = P1;
        snap.currentState = YanivGameEngine.GameState.GAME_OVER.name();

        YanivGameEngine restored = YanivGameEngine.fromSnapshot(snap.toJson());
        YanivGameEngine roundTripped = YanivGameEngine.fromSnapshot(restored.toSnapshot());

        assertEquals(List.of(P1, P2, P3), roundTripped.getFinishingOrder(),
                "placement must not change because the game was restored");
    }

    @Test
    @DisplayName("A knocked-out player is not a winner of the rounds played after they are out")
    void knockedOutPlayersAreNotRoundWinners() {
        YanivGameEngine seed = new YanivGameEngine("room-7", List.of(P1, P2, P3), 7, 100);
        GameSnapshot snap = GameSnapshot.fromJson(seed.toSnapshot());
        // P3 is already out and holds nothing; P1 and P2 play on.
        snap.eliminatedPlayers = new LinkedHashSet<>(List.of(P3));
        snap.playerHands = new HashMap<>(Map.of(
                P1, hand("card_1", "ACE"),
                P2, hand("card_5", "FIVE"),
                P3, new ArrayList<>()));
        snap.playerScores = new HashMap<>(Map.of(P1, 10, P2, 10, P3, 100));
        snap.currentPlayerIndex = 0;
        snap.currentState = YanivGameEngine.GameState.WAIT_FOR_TURN.name();
        YanivGameEngine engine = YanivGameEngine.fromSnapshot(snap.toJson());

        engine.callYaniv(P1);
        engine.contestYaniv(P2);

        assertEquals(0, engine.getRoundScores().get(P3),
                "precondition: a player who is out is parked on a round score of 0");
        assertEquals(List.of(P1), engine.getRoundWinners(),
                "a round score of 0 for a player who never played is not a win");
    }

    @Test
    @DisplayName("A game with no winner has no finishing order, so nobody is crowned first")
    void drawnGameHasNoFinishingOrder() {
        YanivGameEngine seed = new YanivGameEngine("room-6", List.of(P1, P2, P3), 7, 100);
        GameSnapshot snap = GameSnapshot.fromJson(seed.toSnapshot());
        // Everyone knocked out, nobody left standing.
        snap.eliminatedPlayers = new LinkedHashSet<>(List.of(P3, P2, P1));
        snap.winnerId = null;
        snap.currentState = YanivGameEngine.GameState.GAME_OVER.name();
        YanivGameEngine engine = YanivGameEngine.fromSnapshot(snap.toJson());

        assertTrue(engine.getFinishingOrder().isEmpty(),
                "reverse elimination order would otherwise hand first place to the last player out");
    }

    // ==========================================
    // The engine resolves pile draws itself
    // ==========================================

    @Test
    @DisplayName("A draw takes the real pile card, not the caller's copy of it")
    void drawResolvesTheCardFromThePile() {
        YanivGameEngine engine = new YanivGameEngine("room-5", List.of(P1, P2), 200, 200);
        String current = engine.getCurrentPlayer();

        Card realCard = engine.getDiscardPile().getDrawableCards().get(0);
        // Same id, deliberately wrong rank and suit. Card equality is the id alone.
        Card impostor = new Card(realCard.getId(), Card.Suit.SPADES, Card.Rank.KING);

        engine.processDiscard(current, List.of(engine.getPlayerHand(current).getCards().get(0)));
        engine.processDraw(current, "DISCARD_PILE", impostor);

        Card inHand = engine.getPlayerHand(current).getCardById(realCard.getId()).orElseThrow();
        assertEquals(realCard.getRank(), inHand.getRank(), "the pile's rank must win, not the caller's");
        assertEquals(realCard.getSuit(), inHand.getSuit(), "the pile's suit must win, not the caller's");
    }
}
