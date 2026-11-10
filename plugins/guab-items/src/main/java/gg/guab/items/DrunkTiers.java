package gg.guab.items;

/**
 * Pure drunk-meter thresholds. The effect enforcement lives in
 * {@link DrunkEffects}; keeping the numbers here makes them testable without a
 * running server.
 */
final class DrunkTiers {

    static final double SOBER = 0.0;
    static final double TIPSY = 3.0;
    static final double MERRY = 5.0;
    static final double DRUNK = 7.0;
    static final double WRECKED = 9.5;
    static final double ABSOLUTELY_WETHERSPOONS = 13.0;

    private static final double[] MINIMUMS = {
            SOBER, TIPSY, MERRY, DRUNK, WRECKED, ABSOLUTELY_WETHERSPOONS
    };

    private DrunkTiers() {
    }

    /** Tier level 0 (sober) through 5 (absolutely Wetherspoons). */
    static int levelFor(double units) {
        int level = 0;
        for (int index = 0; index < MINIMUMS.length; index++) {
            if (units >= MINIMUMS[index]) {
                level = index;
            }
        }
        return level;
    }

    static double minimumUnits(int level) {
        return MINIMUMS[Math.max(0, Math.min(MINIMUMS.length - 1, level))];
    }
}
