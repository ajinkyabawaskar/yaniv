package shop.abwork.yanif.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a Yaniv call resolves: who may contest it, and who is credited with an Asaf
 * when two opponents tie for the lowest hand.
 */
public class YanivResolutionTest {

    // Seat order is deliberate: a HashMap visits "player-c" before "player-b", so a
    // tie-break that iterates the score map instead of the seat list picks player-c.
    private static final String CALLER = "player-a";
    private static final String SEAT_2 = "player-b";
    private static final String SEAT_3 = "player-c";

    /**
     * Build an engine whose players hold exactly the given cards, via the snapshot
     * restore path. Dealing is random, so this is the only way to pin hand values.
     */
    private YanivGameEngine engineWithHands(Map<String, List<GameSnapshot.CardDto>> hands) {
        YanivGameEngine seed = new YanivGameEngine("room-1", List.of(CALLER, SEAT_2, SEAT_3), 7, 200);
        GameSnapshot snapshot = GameSnapshot.fromJson(seed.toSnapshot());
        snapshot.playerHands = new java.util.HashMap<>(hands);
        snapshot.currentPlayerIndex = 0;
        snapshot.currentState = YanivGameEngine.GameState.WAIT_FOR_TURN.name();
        return YanivGameEngine.fromSnapshot(snapshot.toJson());
    }

    /** A hand of one card of the given rank, worth that rank's value. */
    private List<GameSnapshot.CardDto> hand(String cardId, String rank) {
        List<GameSnapshot.CardDto> cards = new ArrayList<>();
        cards.add(new GameSnapshot.CardDto(cardId, "HEARTS", rank));
        return cards;
    }

    @Test
    @DisplayName("Two opponents tied for lowest: the Asaf goes to the earlier seat, not hash order")
    void asafTieBreakFollowsSeatOrder() {
        // Caller holds 7; both opponents hold 5, tied and strictly lower.
        YanivGameEngine engine = engineWithHands(Map.of(
                CALLER, hand("card_7", "SEVEN"),
                SEAT_2, hand("card_5", "FIVE"),
                SEAT_3, hand("card_18", "FIVE")));

        engine.callYaniv(CALLER);
        engine.contestYaniv(SEAT_2);

        assertTrue(engine.isAsaf(), "precondition: both opponents are strictly below the caller");
        assertEquals(SEAT_2, engine.getAsafByUserId(),
                "a tie for the lowest hand must resolve to the earlier seat, deterministically");
        assertEquals(0, engine.getRoundScores().get(SEAT_2), "the Asaf player scores 0");
        assertEquals(5, engine.getRoundScores().get(SEAT_3), "the tied loser takes their hand");
        assertEquals(7 + 30, engine.getRoundScores().get(CALLER), "the caller takes hand + 30");
    }

    @Test
    @DisplayName("The tie-break is stable across repeated resolutions")
    void asafTieBreakIsStable() {
        for (int i = 0; i < 20; i++) {
            YanivGameEngine engine = engineWithHands(Map.of(
                    CALLER, hand("card_7", "SEVEN"),
                    SEAT_2, hand("card_5", "FIVE"),
                    SEAT_3, hand("card_18", "FIVE")));
            engine.callYaniv(CALLER);
            engine.contestYaniv(SEAT_3);
            assertEquals(SEAT_2, engine.getAsafByUserId(), "unstable tie-break on iteration " + i);
        }
    }

    @Test
    @DisplayName("A player who is not in the game cannot contest a Yaniv call")
    void nonMemberCannotContest() {
        YanivGameEngine engine = engineWithHands(Map.of(
                CALLER, hand("card_7", "SEVEN"),
                SEAT_2, hand("card_5", "FIVE"),
                SEAT_3, hand("card_18", "FIVE")));

        engine.callYaniv(CALLER);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> engine.contestYaniv("some-stranger"),
                "an outsider who knows the room id must not be able to resolve the round");
        assertTrue(error.getMessage().contains("Only players in this game"),
                "unexpected message: " + error.getMessage());

        assertTrue(engine.isYanivCalled(), "the call must still be open after a rejected contest");
    }

    @Test
    @DisplayName("A player in the game can still contest")
    void memberCanStillContest() {
        YanivGameEngine engine = engineWithHands(Map.of(
                CALLER, hand("card_7", "SEVEN"),
                SEAT_2, hand("card_5", "FIVE"),
                SEAT_3, hand("card_18", "FIVE")));

        engine.callYaniv(CALLER);
        assertDoesNotThrow(() -> engine.contestYaniv(SEAT_3));
        assertFalse(engine.isYanivCalled(), "a valid contest resolves the round immediately");
    }
}
