package gg.guab.items;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the shortest human-readable checkout route for the action bar. The
 * search is tiny (at most three darts) and cached, so it is safe to ask for a
 * hint on every HUD tick.
 */
public final class DartsCheckout {

    private static final List<Segment> SEGMENTS = buildSegments();
    private static final ConcurrentHashMap<Integer, String> CACHE = new ConcurrentHashMap<>();

    private DartsCheckout() {
    }

    /** Returns something like {@code T20 T20 BULL}, or null when there is no finish. */
    public static String hint(int remaining, int dartsLeft) {
        if (remaining <= 0 || remaining > 170 || dartsLeft <= 0) {
            return null;
        }
        int key = (remaining << 3) | Math.min(7, dartsLeft);
        return CACHE.computeIfAbsent(key, ignored -> {
            for (int darts = 1; darts <= dartsLeft; darts++) {
                List<String> route = new ArrayList<>(3);
                if (search(remaining, darts, route)) {
                    return String.join(" ", route);
                }
            }
            return null;
        });
    }

    private static boolean search(int remaining, int dartsLeft, List<String> route) {
        if (dartsLeft == 0) {
            return remaining == 0;
        }
        for (Segment segment : SEGMENTS) {
            if (segment.score > remaining) {
                continue;
            }
            int next = remaining - segment.score;
            if (next == 1) {
                continue;
            }
            route.add(segment.label);
            if (dartsLeft == 1) {
                if (next == 0 && segment.doubleOut) {
                    return true;
                }
            } else if (search(next, dartsLeft - 1, route)) {
                return true;
            }
            route.remove(route.size() - 1);
        }
        return false;
    }

    private static List<Segment> buildSegments() {
        List<Segment> segments = new ArrayList<>(64);
        for (int n = 20; n >= 1; n--) {
            segments.add(new Segment(n * 3, "T" + n, false));
        }
        segments.add(new Segment(50, "BULL", true));
        segments.add(new Segment(25, "25", false));
        for (int n = 20; n >= 1; n--) {
            segments.add(new Segment(n * 2, "D" + n, true));
        }
        for (int n = 20; n >= 1; n--) {
            segments.add(new Segment(n, "S" + n, false));
        }
        return List.copyOf(segments);
    }

    private record Segment(int score, String label, boolean doubleOut) {
    }
}
