package shop.abwork.yanif.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.DiscardCombination;
import shop.abwork.yanif.game.model.DiscardPile;
import shop.abwork.yanif.game.validator.CardCombinationValidator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests strictly validating all rules from docs/yaniv-rules.md:
 * - Allowed discard types (Single, Set, Sequence, Hand-Clearing Mixed Run)
 * - Strict constraints (No corner wrap K-A-2, no duplicate rank runs, no mixed runs with remaining cards)
 * - Pickup rules (Exact one-card, outer-only for sequences, any-card for sets)
 */
public class YanivRulesTest {

    private DiscardPile discardPile;

    @BeforeEach
    void setUp() {
        discardPile = new DiscardPile();
    }

    // ==========================================
    // 1. DISCARD RULES TESTS
    // ==========================================

    @Test
    @DisplayName("Single Card Discards: Any single card is valid")
    void testSingleCardDiscard() {
        Card c1 = new Card("c1", Card.Suit.HEARTS, Card.Rank.SEVEN);
        assertTrue(CardCombinationValidator.isValidCombination(List.of(c1), 5));
    }

    @Test
    @DisplayName("Set Discards: Same rank (2 to 4 cards) is valid")
    void testSetDiscards() {
        Card c1 = new Card("c1", Card.Suit.SPADES, Card.Rank.EIGHT);
        Card c2 = new Card("c2", Card.Suit.DIAMONDS, Card.Rank.EIGHT);
        Card c3 = new Card("c3", Card.Suit.CLUBS, Card.Rank.EIGHT);
        Card c4 = new Card("c4", Card.Suit.HEARTS, Card.Rank.EIGHT);

        // 2-card set (pair)
        assertTrue(CardCombinationValidator.isValidCombination(List.of(c1, c2), 5));
        // 3-card set
        assertTrue(CardCombinationValidator.isValidCombination(List.of(c1, c2, c3), 5));
        // 4-card set
        assertTrue(CardCombinationValidator.isValidCombination(List.of(c1, c2, c3, c4), 5));

        // Different ranks in set is invalid
        Card cDiff = new Card("c5", Card.Suit.HEARTS, Card.Rank.NINE);
        assertFalse(CardCombinationValidator.isValidCombination(List.of(c1, c2, cDiff), 5));
    }

    @Test
    @DisplayName("Sequence Discards: Same suit consecutive (Ace-low and Ace-high)")
    void testSequenceDiscards() {
        // Ace-low: A-2-3 of Hearts
        Card aH = new Card("aH", Card.Suit.HEARTS, Card.Rank.ACE);
        Card twoH = new Card("2H", Card.Suit.HEARTS, Card.Rank.TWO);
        Card threeH = new Card("3H", Card.Suit.HEARTS, Card.Rank.THREE);
        assertTrue(CardCombinationValidator.isValidCombination(List.of(aH, twoH, threeH), 5));

        // Ace-high: Q-K-A of Spades
        Card qS = new Card("qS", Card.Suit.SPADES, Card.Rank.QUEEN);
        Card kS = new Card("kS", Card.Suit.SPADES, Card.Rank.KING);
        Card aS = new Card("aS", Card.Suit.SPADES, Card.Rank.ACE);
        assertTrue(CardCombinationValidator.isValidCombination(List.of(qS, kS, aS), 5));

        // Standard run: 3-4-5 of Hearts
        Card fourH = new Card("4H", Card.Suit.HEARTS, Card.Rank.FOUR);
        Card fiveH = new Card("5H", Card.Suit.HEARTS, Card.Rank.FIVE);
        assertTrue(CardCombinationValidator.isValidCombination(List.of(threeH, fourH, fiveH), 5));
    }

    @Test
    @DisplayName("Sequence Constraint: Corner-wrapping (K-A-2) is strictly ILLEGAL")
    void testCornerWrappingSequenceIllegal() {
        Card kS = new Card("kS", Card.Suit.SPADES, Card.Rank.KING);
        Card aS = new Card("aS", Card.Suit.SPADES, Card.Rank.ACE);
        Card twoS = new Card("2S", Card.Suit.SPADES, Card.Rank.TWO);

        assertFalse(CardCombinationValidator.isValidCombination(List.of(kS, aS, twoS), 5));
    }

    @Test
    @DisplayName("Sequence Constraint: Duplicate rank sequences are illegal")
    void testDuplicateRankSequenceIllegal() {
        Card fourH1 = new Card("4H1", Card.Suit.HEARTS, Card.Rank.FOUR);
        Card fourH2 = new Card("4H2", Card.Suit.HEARTS, Card.Rank.FOUR);
        Card fiveH = new Card("5H", Card.Suit.HEARTS, Card.Rank.FIVE);

        assertFalse(CardCombinationValidator.isValidCombination(List.of(fourH1, fourH2, fiveH), 5));
    }

    @Test
    @DisplayName("Special Rule: Mixed-Suit Sequence ONLY valid if clearing entire hand")
    void testMixedSuitHandClearSequence() {
        Card fourH = new Card("4H", Card.Suit.HEARTS, Card.Rank.FOUR);
        Card fiveS = new Card("5S", Card.Suit.SPADES, Card.Rank.FIVE);
        Card sixD = new Card("6D", Card.Suit.DIAMONDS, Card.Rank.SIX);
        List<Card> mixedRun = List.of(fourH, fiveS, sixD);

        // Valid if handSize == 3 (clearing entire hand)
        assertTrue(CardCombinationValidator.isValidCombination(mixedRun, 3));

        // Strictly ILLEGAL if handSize == 5 (cards remain in hand)
        assertFalse(CardCombinationValidator.isValidCombination(mixedRun, 5));
    }

    // ==========================================
    // 2. DISCARD PILE PICKUP RULES TESTS (docs/yaniv-rules.md Matrix)
    // ==========================================

    @Test
    @DisplayName("Pickup Test Case 1: [7♥] -> Pick [7♥] (VALID)")
    void testPickupTestCase1_Single() {
        Card sevenH = new Card("7H", Card.Suit.HEARTS, Card.Rank.SEVEN);
        discardPile.addCombination(List.of(sevenH), DiscardCombination.Type.SINGLE, -1);

        assertTrue(discardPile.isDrawable("7H"));
        assertEquals(1, discardPile.getDrawableCards().size());
        assertEquals("7H", discardPile.getDrawableCards().get(0).getId());
    }

    @Test
    @DisplayName("Pickup Test Case 2: [8♠, 8♦, 8♣] -> Pick ANY one card [8♦] (VALID)")
    void testPickupTestCase2_SetAnyCard() {
        Card eightS = new Card("8S", Card.Suit.SPADES, Card.Rank.EIGHT);
        Card eightD = new Card("8D", Card.Suit.DIAMONDS, Card.Rank.EIGHT);
        Card eightC = new Card("8C", Card.Suit.CLUBS, Card.Rank.EIGHT);
        discardPile.addCombination(List.of(eightS, eightD, eightC), DiscardCombination.Type.SET, 5);

        // All 3 individual cards in the set are eligible for pickup
        assertTrue(discardPile.isDrawable("8S"));
        assertTrue(discardPile.isDrawable("8D"));
        assertTrue(discardPile.isDrawable("8C"));
        assertEquals(3, discardPile.getDrawableCards().size());
    }

    @Test
    @DisplayName("Pickup Test Case 4, 5, 6: [3♥, 4♥, 5♥] Sequence -> Pick ends [3♥], [5♥] VALID, middle [4♥] INVALID")
    void testPickupTestCase4_5_6_SequenceEndsOnly() {
        Card threeH = new Card("3H", Card.Suit.HEARTS, Card.Rank.THREE);
        Card fourH = new Card("4H", Card.Suit.HEARTS, Card.Rank.FOUR);
        Card fiveH = new Card("5H", Card.Suit.HEARTS, Card.Rank.FIVE);

        // Passed in unsorted order to test automatic sequence sorting
        discardPile.addCombination(List.of(fiveH, threeH, fourH), DiscardCombination.Type.SEQUENCE, 5);

        // Ends (3♥ and 5♥) must be drawable
        assertTrue(discardPile.isDrawable("3H"), "Lowest card 3H should be drawable");
        assertTrue(discardPile.isDrawable("5H"), "Highest card 5H should be drawable");

        // Middle card (4♥) must be LOCKED / NOT drawable
        assertFalse(discardPile.isDrawable("4H"), "Middle card 4H must NOT be drawable");
        assertEquals(2, discardPile.getDrawableCards().size());
    }

    @Test
    @DisplayName("Pickup Test Case 7, 8: [4♥, 5♠, 6♦] (Mixed Hand Clear) -> Pick ends [4♥], [6♦] VALID, middle [5♠] INVALID")
    void testPickupTestCase7_8_MixedSequenceEndsOnly() {
        Card fourH = new Card("4H", Card.Suit.HEARTS, Card.Rank.FOUR);
        Card fiveS = new Card("5S", Card.Suit.SPADES, Card.Rank.FIVE);
        Card sixD = new Card("6D", Card.Suit.DIAMONDS, Card.Rank.SIX);

        discardPile.addCombination(List.of(fourH, fiveS, sixD), DiscardCombination.Type.MIXED_SEQUENCE, 3);

        assertTrue(discardPile.isDrawable("4H"), "First card 4H of mixed sequence should be drawable");
        assertTrue(discardPile.isDrawable("6D"), "Last card 6D of mixed sequence should be drawable");
        assertFalse(discardPile.isDrawable("5S"), "Middle card 5S of mixed sequence must NOT be drawable");
        assertEquals(2, discardPile.getDrawableCards().size());
    }
}
