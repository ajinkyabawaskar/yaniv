package shop.abwork.yanif.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offered score limits are written down twice — here and in the client's picker — so
 * they must be pinned together, the way the discard rules are.
 *
 * Drift is silent and one-directional: the client would offer a limit the server refuses,
 * and the host would click it and see nothing happen.
 */
class ScoreLimitsContractTest {

    private static final Path CLIENT = Path.of("frontend/src/utils/scoreLimits.ts");

    @Test
    @DisplayName("The client offers exactly the limits the server accepts, in the same order")
    void theClientOffersWhatTheServerAccepts() throws Exception {
        String client = Files.readString(CLIENT);

        Matcher declaration = Pattern.compile("export const SCORE_LIMITS = \\[([^\\]]*)\\]").matcher(client);
        assertTrue(declaration.find(), "could not find SCORE_LIMITS in " + CLIENT);

        List<Integer> offered = new ArrayList<>();
        Matcher numbers = Pattern.compile("\\d+").matcher(declaration.group(1));
        while (numbers.find()) {
            offered.add(Integer.valueOf(numbers.group()));
        }

        assertEquals(List.copyOf(ScoreLimits.supported()), offered,
                "add the limit to both ScoreLimits.java and scoreLimits.ts, or the picker "
                        + "offers something the server rejects");
    }

    @Test
    @DisplayName("The client's default is the server's default")
    void theDefaultsAgree() throws Exception {
        String client = Files.readString(CLIENT);

        Matcher declaration = Pattern.compile("export const DEFAULT_SCORE_LIMIT = (\\d+)").matcher(client);
        assertTrue(declaration.find(), "could not find DEFAULT_SCORE_LIMIT in " + CLIENT);

        assertEquals(ScoreLimits.DEFAULT, Integer.parseInt(declaration.group(1)),
                "a client defaulting elsewhere would show the wrong limit until state arrives");
    }

    @Test
    @DisplayName("Every offered limit is a multiple of 50, which is what makes them play differently")
    void everyLimitIsAHalvingPoint() {
        for (Integer limit : ScoreLimits.supported()) {
            assertEquals(0, limit % 50,
                    limit + " is not a halving point, so landing on it exactly would eliminate "
                            + "rather than halve -- a different game from the documented one");
        }
    }
}
