package gg.guab.marriage;

import gg.guab.core.PointsService;

import java.util.UUID;

/**
 * Tiny bridge between marriage fees and the shared points ledger. Kept separate
 * so affordability can be tested with a fake {@link PointsService} and no server.
 */
public final class MarriageEconomy {

    private MarriageEconomy() {
    }

    /** Whether the player currently has enough points for the fee. */
    public static boolean canAfford(PointsService points, UUID player, long cost) {
        if (cost <= 0L) {
            return true;
        }
        if (points == null) {
            // GuabCore is a hard dependency, but degrade to free rather than block weddings.
            return true;
        }
        return player != null && points.get(player) >= cost;
    }

    /**
     * Atomically takes the fee. A missing ledger or zero fee means free; a null
     * player with a real fee never succeeds.
     */
    public static boolean charge(PointsService points, UUID player, long cost) {
        if (cost <= 0L) {
            return true;
        }
        if (points == null) {
            return true;
        }
        return player != null && points.take(player, cost);
    }
}
