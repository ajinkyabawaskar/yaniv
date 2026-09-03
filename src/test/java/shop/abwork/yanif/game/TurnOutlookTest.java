package shop.abwork.yanif.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.DiscardCombination;
import shop.abwork.yanif.game.model.DiscardPile;
import shop.abwork.yanif.game.model.Hand;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared model of a turn: what a hand can legally shed, and the hand score each
 * line leaves behind once the mandatory draw is counted.
 */
class TurnOutlookTest {

    private static Card card(String id, Card.Suit suit, Card.Rank rank) {
        return new Card(id, suit, rank);
    }

    private static Hand hand(Card... cards) {
        return new Hand(List.of(cards));
    }

    private static DiscardPile pileWith(Card... cards) {
        DiscardPile pile = new DiscardPile();
        for (Card c : cards) {
            pile.addCombination(List.of(c), DiscardCombination.Type.SINGLE, -1);
        }
        return pile;
    }

    // ==========================================
    // A turn always ends holding a card
    // ==========================================

    @Test
    @DisplayName("The drawn card is counted: emptying the hand does not reach a hand score of 0")
    void theMandatoryDrawIsCounted() {
        // A-2-3-4 of spades is one legal run that clears the hand. The player still has
        // to draw, so they end the turn holding something.
        Hand h = hand(
                card("s4", Card.Suit.SPADES, Card.Rank.FOUR),
                card("s3", Card.Suit.SPADES, Card.Rank.THREE),
                card("s2", Card.Suit.SPADES, Card.Rank.TWO),
                card("sA", Card.Suit.SPADES, Card.Rank.ACE));

        TurnOutlook.Move best = TurnOutlook.bestMove(h, new DiscardPile());

        assertEquals(4, best.discard().size(), "precondition: the whole hand goes");
        assertEquals(TurnOutlook.AVERAGE_DECK_CARD_VALUE, best.resultingHandScore(),
                "an emptied hand still ends the turn holding one drawn card");
    }

    @Test
    @DisplayName("A pile card is added to the hand, not swapped for the worst card in it")
    void takingFromThePileAddsRatherThanSwaps() {
        // 9S 7H = 16 (ranks deliberately not consecutive, so the hand is not itself a
        // mixed-suit run). The pile offers an Ace. Discarding the 9 leaves 7, plus the
        // Ace drawn on top = 8. The old swap model scored BOTH singles at 1, because it
        // believed the Ace replaced whatever was left, and then broke the tie on card id
        // -- throwing away the 7 and keeping the 9.
        Hand h = hand(
                card("s9", Card.Suit.SPADES, Card.Rank.NINE),
                card("h7", Card.Suit.HEARTS, Card.Rank.SEVEN));
        DiscardPile pile = pileWith(card("pileA", Card.Suit.DIAMONDS, Card.Rank.ACE));

        TurnOutlook.Move best = TurnOutlook.bestMove(h, pile);

        assertEquals(List.of("s9"), best.discard().stream().map(Card::getId).toList(),
                "the bigger card has to go; a draw cannot cancel out the card left behind");
        assertEquals("DISCARD_PILE", best.drawSource());
        assertEquals(8, best.resultingHandScore(), "7 left in hand + the Ace drawn on top");
    }

    @Test
    @DisplayName("An unknown deck card is priced at the deck average, not treated as free")
    void aDeckDrawIsNotFree() {
        // 9S 7H = 16 with nothing to take from the pile.
        Hand h = hand(
                card("s9", Card.Suit.SPADES, Card.Rank.NINE),
                card("h7", Card.Suit.HEARTS, Card.Rank.SEVEN));

        TurnOutlook.Move best = TurnOutlook.bestMove(h, new DiscardPile());

        assertEquals("DECK", best.drawSource());
        assertEquals(7 + TurnOutlook.AVERAGE_DECK_CARD_VALUE, best.resultingHandScore(),
                "the 7 left behind plus whatever the deck is expected to hand over");
    }

    @Test
    @DisplayName("The pile is taken only when it beats an average deck card")
    void thePileIsTakenOnlyWhenItBeatsTheDeck() {
        Hand h = hand(
                card("sK", Card.Suit.SPADES, Card.Rank.KING),
                card("h8", Card.Suit.HEARTS, Card.Rank.EIGHT));

        assertEquals("DISCARD_PILE",
                TurnOutlook.bestMove(h, pileWith(card("p2", Card.Suit.CLUBS, Card.Rank.TWO))).drawSource(),
                "a 2 is cheaper than an average deck card");
        assertEquals("DECK",
                TurnOutlook.bestMove(h, pileWith(card("p10", Card.Suit.CLUBS, Card.Rank.TEN))).drawSource(),
                "a 10 is dearer than an average deck card, so gamble on the deck");
    }

    // ==========================================
    // Enumeration matches what the engine accepts
    // ==========================================

    @Test
    @DisplayName("Combinations count: a run sheds more than any single card in it")
    void combinationsBeatSingles() {
        // 5H 5D KS QS 9S = 39. The K-Q run sheds 20; the best single sheds 10.
        Hand h = hand(
                card("h5", Card.Suit.HEARTS, Card.Rank.FIVE),
                card("d5", Card.Suit.DIAMONDS, Card.Rank.FIVE),
                card("sK", Card.Suit.SPADES, Card.Rank.KING),
                card("sQ", Card.Suit.SPADES, Card.Rank.QUEEN),
                card("s9", Card.Suit.SPADES, Card.Rank.NINE));

        TurnOutlook.Move best = TurnOutlook.bestMove(h, new DiscardPile());

        assertEquals(List.of("sK", "sQ"), best.discard().stream().map(Card::getId).sorted().toList());
    }

    @Test
    @DisplayName("Two hands worth the same points rank differently when one of them is a set")
    void theSameHandScoreCanBeADifferentDistance() {
        // Both hands are worth 30. One is three Kings -- one discard away from nothing.
        // The other is four unrelated cards that can only go one at a time.
        Hand asASet = hand(
                card("sK", Card.Suit.SPADES, Card.Rank.KING),
                card("hK", Card.Suit.HEARTS, Card.Rank.KING),
                card("dK", Card.Suit.DIAMONDS, Card.Rank.KING));
        Hand asSingletons = hand(
                card("cK", Card.Suit.CLUBS, Card.Rank.KING),
                card("h9", Card.Suit.HEARTS, Card.Rank.NINE),
                card("d6", Card.Suit.DIAMONDS, Card.Rank.SIX),
                card("s5", Card.Suit.SPADES, Card.Rank.FIVE));

        assertEquals(asASet.calculateScore(), asSingletons.calculateScore(),
                "precondition: identical hand scores");
        assertTrue(TurnOutlook.lowestReachableHandScore(asASet, new DiscardPile())
                        < TurnOutlook.lowestReachableHandScore(asSingletons, new DiscardPile()),
                "a hand that sheds in one discard is closer to Yaniv than one that cannot");
    }

    @Test
    @DisplayName("Every enumerated discard is one the engine actually accepts")
    void everyEnumeratedDiscardIsLegal() {
        YanivGameEngine engine = new YanivGameEngine("room-outlook", List.of("p1", "p2"), 7, 100);
        Hand h = engine.getPlayerHand(engine.getCurrentPlayer());

        List<List<Card>> discards = TurnOutlook.legalDiscards(h);

        assertFalse(discards.isEmpty(), "a hand always has at least its singles");
        for (List<Card> combo : discards) {
            assertTrue(shop.abwork.yanif.game.validator.CardCombinationValidator
                            .isValidCombination(combo, h.size()),
                    "enumerated an illegal discard: " + combo);
        }
    }
}
