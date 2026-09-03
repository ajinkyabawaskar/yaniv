package shop.abwork.yanif.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a knocked-out player is told about the players still in the game: how close each
 * one is to calling Yaniv, and how close each one is to being knocked out themselves.
 */
class SpectatorReadingsTest {

    private static final String P1 = "player-a";
    private static final String P2 = "player-b";
    private static final String P3 = "player-c";

    private static final int THRESHOLD = 7;
    private static final int TARGET = 100;

    /** An engine mid-round with exact hands, running scores and knocked-out players. */
    private YanivGameEngine craft(Map<String, List<GameSnapshot.CardDto>> hands,
                                  Map<String, Integer> scores,
                                  Set<String> eliminated) {
        YanivGameEngine seed = new YanivGameEngine("room-spec", List.of(P1, P2, P3), THRESHOLD, TARGET);
        GameSnapshot snap = GameSnapshot.fromJson(seed.toSnapshot());
        snap.playerHands = new HashMap<>(hands);
        snap.playerScores = new HashMap<>(scores);
        snap.eliminatedPlayers = new LinkedHashSet<>(eliminated);
        snap.currentPlayerIndex = 0;
        snap.currentState = YanivGameEngine.GameState.WAIT_FOR_TURN.name();
        return YanivGameEngine.fromSnapshot(snap.toJson());
    }

    private static List<GameSnapshot.CardDto> cards(String... suitRankPairs) {
        List<GameSnapshot.CardDto> hand = new ArrayList<>();
        for (int i = 0; i < suitRankPairs.length; i += 2) {
            hand.add(new GameSnapshot.CardDto(
                    "c" + i + "_" + suitRankPairs[i] + "_" + suitRankPairs[i + 1],
                    suitRankPairs[i], suitRankPairs[i + 1]));
        }
        return hand;
    }

    @Test
    @DisplayName("Only players still in the game are reported on")
    void knockedOutPlayersAreNotReportedOn() {
        YanivGameEngine engine = craft(
                Map.of(P1, cards("HEARTS", "KING", "SPADES", "NINE"),
                       P2, cards("CLUBS", "EIGHT", "DIAMONDS", "SIX"),
                       P3, new ArrayList<>()),
                Map.of(P1, 10, P2, 20, P3, TARGET),
                Set.of(P3));

        Map<String, YanivGameEngine.SpectatorReading> readings = engine.getSpectatorReadings();

        assertEquals(Set.of(P1, P2), readings.keySet(),
                "a player who is out has no hand to read and no race left to run");
    }

    @Test
    @DisplayName("Everyone who can already call Yaniv reads identically, with no number to compare")
    void playersInYanivRangeAreIndistinguishable() {
        // P1 holds 3, P2 holds 6. Both can call; the meter must not say which is lower.
        YanivGameEngine engine = craft(
                Map.of(P1, cards("HEARTS", "THREE"),
                       P2, cards("CLUBS", "SIX"),
                       P3, cards("SPADES", "KING", "DIAMONDS", "NINE")),
                Map.of(P1, 10, P2, 10, P3, 10),
                Set.of());

        Map<String, YanivGameEngine.SpectatorReading> readings = engine.getSpectatorReadings();

        assertTrue(readings.get(P1).canCallYanivNow());
        assertTrue(readings.get(P2).canCallYanivNow());
        assertNull(readings.get(P1).lowestReachableHandScore(),
                "a player in Yaniv range must carry no hand-score number");
        assertEquals(readings.get(P1).lowestReachableHandScore(),
                readings.get(P2).lowestReachableHandScore(),
                "3 and 6 are both 'about to win' and must be told apart by nothing");
        assertFalse(readings.get(P3).canCallYanivNow());
    }

    @Test
    @DisplayName("A player out of range is measured on what they could reach, not what they hold")
    void outOfRangePlayersAreMeasuredOnWhatTheyCouldReach() {
        // Both hands are worth 30. P1 holds three Kings, one discard from nothing.
        // P2 holds four unrelated cards it can only shed one at a time.
        YanivGameEngine engine = craft(
                Map.of(P1, cards("HEARTS", "KING", "SPADES", "KING", "DIAMONDS", "KING"),
                       P2, cards("CLUBS", "KING", "HEARTS", "NINE", "DIAMONDS", "SIX", "SPADES", "FIVE"),
                       P3, cards("CLUBS", "TWO")),
                Map.of(P1, 10, P2, 10, P3, 10),
                Set.of());

        Map<String, YanivGameEngine.SpectatorReading> readings = engine.getSpectatorReadings();

        assertTrue(readings.get(P1).lowestReachableHandScore()
                        < readings.get(P2).lowestReachableHandScore(),
                "identical hand scores, but a set sheds in one turn and singletons do not");
    }

    @Test
    @DisplayName("Distance to elimination is running score against the room's target")
    void distanceToEliminationCountsDownToTheTarget() {
        YanivGameEngine engine = craft(
                Map.of(P1, cards("HEARTS", "KING"),
                       P2, cards("CLUBS", "KING"),
                       P3, cards("SPADES", "KING")),
                Map.of(P1, 5, P2, 95, P3, 0),
                Set.of());

        Map<String, YanivGameEngine.SpectatorReading> readings = engine.getSpectatorReadings();

        assertEquals(95, readings.get(P1).pointsFromElimination());
        assertEquals(5, readings.get(P2).pointsFromElimination(),
                "the running score is what decides the game, not the hand");
        assertEquals(TARGET, readings.get(P3).pointsFromElimination());
    }

    @Test
    @DisplayName("Distance to elimination never reads below zero")
    void distanceToEliminationIsFlooredAtZero() {
        // A score at or past the target with the elimination check not yet run.
        YanivGameEngine engine = craft(
                Map.of(P1, cards("HEARTS", "KING"), P2, cards("CLUBS", "KING"), P3, cards("SPADES", "KING")),
                Map.of(P1, TARGET + 20, P2, 10, P3, 10),
                Set.of());

        assertEquals(0, engine.getSpectatorReadings().get(P1).pointsFromElimination());
    }
}
