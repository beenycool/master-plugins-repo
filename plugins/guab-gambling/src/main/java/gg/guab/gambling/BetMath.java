package gg.guab.gambling;

/**
 * Pure bet and payout arithmetic shared by the table and its tests. Keeping the
 * guards here means the chat flow and the chest UI cannot disagree, and payouts
 * use the 64-bit points ledger rather than the old 32-bit ceiling.
 */
final class BetMath {

    private BetMath() {
    }

    /** True when {@code bet} is positive and inside the configured table limits. */
    static boolean validBet(long bet, long min, long max) {
        long floor = Math.max(1L, min);
        return bet >= floor && (max <= 0L || bet <= max);
    }

    /** Doubling is only safe while the doubled stake still fits in a {@code long}. */
    static boolean canDouble(long bet) {
        return bet > 0L && bet <= Long.MAX_VALUE / 2L;
    }

    /** Clamp a payout to a non-negative ledger value. */
    static long payout(long amount) {
        return Math.max(0L, amount);
    }

    /** A normal or dealer-bust win returns the stake plus the stake. */
    static long winPayout(long bet) {
        if (!canDouble(bet)) {
            return Long.MAX_VALUE;
        }
        return payout(bet * 2L);
    }

    /** A natural blackjack returns the stake plus the configured bonus multiple. */
    static long naturalPayout(long bet, double multiplier) {
        long bonus = Math.round((double) bet * Math.max(0.0, multiplier));
        long total = bet + bonus;
        if (total < bet) {
            return Long.MAX_VALUE;
        }
        return payout(total);
    }
}
