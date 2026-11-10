package gg.guab.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The shared points ledger. Every Guab plugin reads and writes through this service,
 * so gambling, minigames and items all operate on the same balance.
 */
public interface PointsService {

    /** Current balance, or zero for an unknown player. */
    int get(UUID player);

    /** Overwrite a balance. Negative values clamp to zero. */
    void set(UUID player, int amount);

    /** Add points and return the new balance. Negative values clamp at zero. */
    int add(UUID player, int amount);

    /** Take points if the balance covers it. Returns false and changes nothing otherwise. */
    boolean take(UUID player, int amount);

    /** Highest balances first, skipping empty accounts. */
    List<Map.Entry<UUID, Integer>> top(int limit);

    /**
     * Transfers points: either the whole amount moves or nothing does.
     * Returns false when the sender cannot cover the amount. The ledger implementation
     * overrides this with an atomic compare-and-move.
     */
    default boolean transfer(UUID from, UUID to, int amount) {
        if (from == null || to == null || from.equals(to) || amount <= 0) {
            return false;
        }
        if (!take(from, amount)) {
            return false;
        }
        add(to, amount);
        return true;
    }

    /** Known ledger keys, including zero balances. Used to resolve offline names safely. */
    default Set<UUID> knownPlayers() {
        return Set.of();
    }

    /** One-based rank among positive balances, or 0 when the player has no points. */
    default int rank(UUID player) {
        long balance = get(player);
        if (balance <= 0) {
            return 0;
        }
        int rank = 1;
        for (Map.Entry<UUID, Integer> entry : top(Integer.MAX_VALUE)) {
            if (entry.getKey().equals(player)) {
                return rank;
            }
            rank++;
        }
        return 0;
    }
}
