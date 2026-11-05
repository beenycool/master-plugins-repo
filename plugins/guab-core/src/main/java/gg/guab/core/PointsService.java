package gg.guab.core;

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
}
