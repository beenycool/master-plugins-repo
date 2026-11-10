package gg.guab.gambling;

/**
 * Pure bet and payout arithmetic shared by the table and its tests. Keeping the
 * overflow guards here means the chat flow and the chest UI cannot disagree, and
 * every payout stays inside the {@code int} range the points ledger stores.
 */
final class BetMath {

    private BetMath() {
    }

    /** True when {@code bet} is positive and inside the configured table limits. */
    static boolean validBet(int bet, int min, int max) {
        int floor = Math.max(1, min);
        return bet >= floor && (max <= 0 || bet <= max);
    }

    /** Doubling is only safe while the doubled stake still fits in an {@code int}. */
    static boolean canDouble(int bet) {
        return bet > 0 && bet <= Integer.MAX_VALUE / 2;
    }

    /** Clamp a long payout to a ledger-safe, non-negative {@code int}. */
    static int payout(long amount) {
        if (amount <= 0) {
            return 0;
        }
        return (int) Math.min(amount, Integer.MAX_VALUE);
    }

    /** A normal or dealer-bust win returns the stake plus the stake. */
    static int winPayout(int bet) {
        return payout((long) bet * 2L);
    }

    /** A natural blackjack returns the stake plus the configured bonus multiple. */
    static int naturalPayout(int bet, double multiplier) {
        long bonus = Math.round((double) bet * Math.max(0.0, multiplier));
        return payout((long) bet + bonus);
    }
}
