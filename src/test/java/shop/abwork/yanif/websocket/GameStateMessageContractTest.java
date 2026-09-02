package shop.abwork.yanif.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every field the server puts on the wire must be read by the client.
 *
 * A field can be declared on the message, passed down through props, and rendered by a
 * component that is never shown — because nobody ever wrote it from the incoming payload.
 * That is how the bonus discard shipped broken: the engine waited in BONUS_DISCARD for a
 * decision the player had no way to make, and the client sat there retrying its discard.
 * Nothing failed; the feature simply never happened.
 */
class GameStateMessageContractTest {

    private static final Path SERVER = Path.of("src/main/java/shop/abwork/yanif/websocket/GameStateController.java");
    private static final Path CLIENT = Path.of("frontend/src/components/GameView.tsx");

    /**
     * Fields the client is not expected to read, each with a reason. Keep this short: an
     * entry here is a claim that the server is sending something nobody needs.
     */
    private static final Set<String> DELIBERATELY_UNREAD = Set.of(
            // none today
    );

    @Test
    @DisplayName("Every field on GameStateMessage is read by the client")
    void everyFieldSentIsRead() throws Exception {
        String server = Files.readString(SERVER);
        String client = Files.readString(CLIENT);

        int start = server.indexOf("public static class GameStateMessage");
        assertTrue(start > 0, "could not find GameStateMessage in " + SERVER);
        String body = server.substring(start);
        body = body.substring(0, body.indexOf("\n    }"));

        Matcher fields = Pattern.compile("public [\\w<>,\\s\\[\\]]+ (\\w+);").matcher(body);
        List<String> unread = new ArrayList<>();
        int total = 0;
        while (fields.find()) {
            String field = fields.group(1);
            total++;
            if (DELIBERATELY_UNREAD.contains(field)) {
                continue;
            }
            if (!client.contains("gameData." + field)) {
                unread.add(field);
            }
        }

        assertTrue(total > 20, "parsed only " + total + " fields; the regex has probably drifted");
        assertTrue(unread.isEmpty(),
                "the server sends these but " + CLIENT.getFileName() + " never reads them: " + unread
                        + ". Either wire them up, or add them to DELIBERATELY_UNREAD with a reason.");
    }
}
