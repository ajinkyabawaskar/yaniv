package shop.abwork.yanif.game;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.Hand;
import shop.abwork.yanif.game.validator.CardCombinationValidator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Runs the shared rule cases in shared/rules-contract.json against the SERVER
 * implementation. The client runs the same file in
 * frontend/src/utils/yanivRules.contract.test.ts.
 *
 * The discard rules exist twice — once in Java for authority, once in TypeScript so
 * the UI can grey out illegal selections without a round trip. Nothing stops the two
 * drifting apart, so both are pinned to one case table. A rule change that updates
 * only one side fails here or there.
 */
public class RulesContractTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Repo-root relative: tests run from the project root under Maven. */
    private static final Path CONTRACT = Path.of("shared", "rules-contract.json");

    private static JsonNode contract() {
        try {
            return MAPPER.readTree(Files.readString(CONTRACT));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + CONTRACT.toAbsolutePath()
                    + ". The contract file is shared with the frontend test; it must exist.", e);
        }
    }

    private static List<Card> toCards(JsonNode cardsNode) {
        List<Card> cards = new ArrayList<>();
        for (JsonNode card : cardsNode) {
            cards.add(new Card(
                    card.get(0).asString(),
                    Card.Suit.valueOf(card.get(2).asString()),
                    Card.Rank.valueOf(card.get(1).asString())));
        }
        return cards;
    }

    @TestFactory
    Stream<DynamicTest> discardRulesMatchTheSharedContract() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode testCase : contract().get("combinations")) {
            String name = testCase.get("name").asString();
            List<Card> cards = toCards(testCase.get("cards"));
            int handSize = testCase.get("handSize").asInt();
            boolean expected = testCase.get("valid").asBoolean();

            tests.add(dynamicTest(name, () -> assertEquals(
                    expected,
                    CardCombinationValidator.isValidCombination(cards, handSize),
                    "server disagrees with shared/rules-contract.json for: " + name)));
        }
        if (tests.isEmpty()) {
            throw new IllegalStateException("The contract file defined no combination cases");
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> handScoringMatchesTheSharedContract() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode testCase : contract().get("handScores")) {
            String name = testCase.get("name").asString();
            List<Card> cards = toCards(testCase.get("cards"));
            int expected = testCase.get("score").asInt();

            tests.add(dynamicTest(name, () -> assertEquals(
                    expected,
                    new Hand(cards).calculateScore(),
                    "server disagrees with shared/rules-contract.json for: " + name)));
        }
        if (tests.isEmpty()) {
            throw new IllegalStateException("The contract file defined no hand-score cases");
        }
        return tests.stream();
    }
}
