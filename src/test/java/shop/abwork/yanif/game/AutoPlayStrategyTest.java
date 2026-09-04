package shop.abwork.yanif.game;

import org.junit.jupiter.api.Test;
import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.DiscardPile;
import shop.abwork.yanif.game.model.Hand;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoPlayStrategyTest {

    private static Card card(String id, Card.Suit suit, Card.Rank rank) {
        return new Card(id, suit, rank);
    }

    private static Hand hand(Card... cards) {
        return new Hand(List.of(cards));
    }

    private static DiscardPile pileWith(Card.Suit suit, Card.Rank rank) {
        DiscardPile pile = new DiscardPile();
        pile.addCombination(List.of(card("pile_" + suit + "_" + rank, suit, rank)),
                shop.abwork.yanif.game.model.DiscardCombination.Type.SINGLE, -1);
        return pile;
    }

    @Test
    void callsYanivWhenAtOrBelowThreshold() {
        Hand h = hand(
                card("a", Card.Suit.HEARTS, Card.Rank.FIVE),
                card("b", Card.Suit.CLUBS, Card.Rank.TWO));
        assertEquals(AutoPlayStrategy.ActionType.CALL_YANIV,
                AutoPlayStrategy.decide(h, new DiscardPile(), 7).type());

        // Exactly at threshold still calls
        Hand seven = hand(
                card("a", Card.Suit.HEARTS, Card.Rank.FIVE),
                card("b", Card.Suit.CLUBS, Card.Rank.TWO));
        assertEquals(AutoPlayStrategy.ActionType.CALL_YANIV,
                AutoPlayStrategy.decide(seven, new DiscardPile(), 7).type());
    }

    @Test
    void prefersSequenceOverSingleDiscard() {
        // 5H 5D KS QS 9S = 39. Discarding the KS QS run leaves 14, far better
        // than any single (best single leaves 29).
        Hand h = hand(
                card("h5", Card.Suit.HEARTS, Card.Rank.FIVE),
                card("d5", Card.Suit.DIAMONDS, Card.Rank.FIVE),
                card("sK", Card.Suit.SPADES, Card.Rank.KING),
                card("sQ", Card.Suit.SPADES, Card.Rank.QUEEN),
                card("s9", Card.Suit.SPADES, Card.Rank.NINE));

        AutoPlayStrategy.Decision d = AutoPlayStrategy.decide(h, new DiscardPile(), 7);

        assertEquals(AutoPlayStrategy.ActionType.DISCARD_AND_DRAW, d.type());
        assertEquals(List.of("sK", "sQ"),
                d.discardCards().stream().map(Card::getId).sorted().toList());
        assertEquals("DECK", d.drawSource());
    }

    @Test
    void takesImprovingCardFromDiscardPile() {
        // KK 3 = 23; discarding both kings and taking the pile's 2 leaves 3+2=5,
        // beating any other line (single K + take 2 leaves 13).
        Hand h = hand(
                card("sK", Card.Suit.SPADES, Card.Rank.KING),
                card("hK", Card.Suit.HEARTS, Card.Rank.KING),
                card("c3", Card.Suit.CLUBS, Card.Rank.THREE));
        DiscardPile pile = pileWith(Card.Suit.DIAMONDS, Card.Rank.TWO);

        AutoPlayStrategy.Decision d = AutoPlayStrategy.decide(h, pile, 7);

        assertEquals(AutoPlayStrategy.ActionType.DISCARD_AND_DRAW, d.type());
        assertEquals(List.of("hK", "sK"), d.discardCards().stream().map(Card::getId).sorted().toList());
        assertEquals("DISCARD_PILE", d.drawSource());
        assertEquals("pile_DIAMONDS_TWO", d.drawnCardId());
    }

    @Test
    void ignoresHarmfulDiscardPileCardAndDrawsFromDeck() {
        // 9S 5H = 14. Pile offers a KING (worse than anything in hand) - must draw from deck.
        Hand h = hand(
                card("s9", Card.Suit.SPADES, Card.Rank.NINE),
                card("h5", Card.Suit.HEARTS, Card.Rank.FIVE));
        DiscardPile pile = pileWith(Card.Suit.DIAMONDS, Card.Rank.KING);

        AutoPlayStrategy.Decision d = AutoPlayStrategy.decide(h, pile, 7);

        assertEquals("DECK", d.drawSource());
        assertNull(d.drawnCardId());
        assertEquals(List.of("s9"), d.discardCards().stream().map(Card::getId).toList());
    }

    @Test
    void discardsTheBiggerCardEvenWhenThePileOffersACheapOne() {
        // 9S 7H = 16 (not consecutive, so the hand is not itself a mixed-suit run), and
        // the pile offers an Ace. The draw ADDS a card, so the 9 has to go and the turn
        // ends on 7 + 1 = 8. The old swap model priced both singles at 1 -- it thought
        // the Ace replaced whatever was left -- and broke the tie on id, throwing away
        // the 7 and keeping the 9.
        Hand h = hand(
                card("s9", Card.Suit.SPADES, Card.Rank.NINE),
                card("h7", Card.Suit.HEARTS, Card.Rank.SEVEN));
        DiscardPile pile = pileWith(Card.Suit.DIAMONDS, Card.Rank.ACE);

        AutoPlayStrategy.Decision d = AutoPlayStrategy.decide(h, pile, 7);

        assertEquals(List.of("s9"), d.discardCards().stream().map(Card::getId).toList());
        assertEquals("DISCARD_PILE", d.drawSource());
    }

    @Test
    void aceLowSequenceClearingHandIsChosenWhenItReachesZero() {
        // 4S 3S 2S AS (9 points) - ace-low run empties the hand entirely
        Hand h = hand(
                card("s4", Card.Suit.SPADES, Card.Rank.FOUR),
                card("s3", Card.Suit.SPADES, Card.Rank.THREE),
                card("s2", Card.Suit.SPADES, Card.Rank.TWO),
                card("sA", Card.Suit.SPADES, Card.Rank.ACE));

        AutoPlayStrategy.Decision d = AutoPlayStrategy.decide(h, new DiscardPile(), 7);

        assertEquals(AutoPlayStrategy.ActionType.DISCARD_AND_DRAW, d.type());
        assertEquals(List.of("s2", "s3", "s4", "sA"),
                d.discardCards().stream().map(Card::getId).sorted().toList());
    }

    @Test
    void deterministicTieBreakBetweenEqualValueLines() {
        // AS 2S 3S AH 2H 3H = 12. Three lines leave exactly 6: either full suit
        // run (size 3) or the pair of threes (size 2). Tie-break must prefer the
        // larger combo, then smallest ids -> hearts run, stable across calls.
        Hand h = hand(
                card("sA", Card.Suit.SPADES, Card.Rank.ACE),
                card("s2", Card.Suit.SPADES, Card.Rank.TWO),
                card("s3", Card.Suit.SPADES, Card.Rank.THREE),
                card("hA", Card.Suit.HEARTS, Card.Rank.ACE),
                card("h2", Card.Suit.HEARTS, Card.Rank.TWO),
                card("h3", Card.Suit.HEARTS, Card.Rank.THREE));

        AutoPlayStrategy.Decision first = AutoPlayStrategy.decide(h, new DiscardPile(), 7);
        AutoPlayStrategy.Decision second = AutoPlayStrategy.decide(h, new DiscardPile(), 7);

        assertEquals(first.discardCards(), second.discardCards());
        assertEquals(List.of("h2", "h3", "hA"),
                first.discardCards().stream().map(Card::getId).sorted().toList());
        assertTrue(first.discardCards().stream().allMatch(c -> c.getSuit() == Card.Suit.HEARTS));
    }

    @Test
    void everyDecisionIsApplicableToEngine() {
        // Property-style sanity check: whatever is decided must be accepted by the real engine.
        YanivGameEngine engine = new YanivGameEngine("room-auto", List.of("p1", "p2"), 200, 300);
        String player = engine.getCurrentPlayer();

        AutoPlayStrategy.Decision d = AutoPlayStrategy.decide(
                engine.getPlayerHand(player), engine.getDiscardPile(), 200);

        if (d.type() == AutoPlayStrategy.ActionType.CALL_YANIV) {
            engine.callYaniv(player);
            assertTrue(engine.isYanivCalled());
        } else {
            engine.processDiscard(player, d.discardCards());
            Card drawnCard = null;
            if ("DISCARD_PILE".equals(d.drawSource())) {
                drawnCard = engine.getDiscardPile().getDrawableCard(d.drawnCardId()).orElseThrow();
            }
            engine.processDraw(player, d.drawSource(), drawnCard);
            assertNotEquals(player, engine.getCurrentPlayer());
        }
    }
}
