package shop.abwork.yanif.game;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a table may be played to.
 *
 * The engine itself eliminates at whatever {@code targetScore} it is handed, so this is a
 * product decision rather than a rule: the set of limits a host is actually offered. It
 * lives in one place because two entry points let a caller choose one — creating a room
 * over REST, and the host picking one in the waiting room — and they must not drift.
 *
 * Both supported values are multiples of 50, which is what makes them play differently
 * rather than merely longer: halving runs before the elimination test, so landing on the
 * limit exactly halves you to safety. See docs/game-engine.md.
 */
public final class ScoreLimits {

    /** What a table is played to when nobody chooses. */
    public static final int DEFAULT = 100;

    private static final Set<Integer> SUPPORTED = new LinkedHashSet<>(java.util.List.of(100, 200));

    private ScoreLimits() {
    }

    /** The offered limits, in the order a chooser should see them. */
    public static Set<Integer> supported() {
        return java.util.Collections.unmodifiableSet(SUPPORTED);
    }

    public static boolean isSupported(Integer targetScore) {
        return targetScore != null && SUPPORTED.contains(targetScore);
    }

    /** For error messages: "100 or 200". */
    public static String describe() {
        return String.join(" or ", SUPPORTED.stream().map(String::valueOf).toList());
    }
}
