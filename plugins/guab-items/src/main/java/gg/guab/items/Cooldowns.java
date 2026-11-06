package gg.guab.items;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player, per-item cooldowns. Entries are removed lazily once they expire.
 */
public final class Cooldowns {

    private final Map<UUID, Map<ItemType, Long>> until = new HashMap<>();

    public long remainingMillis(UUID player, ItemType type) {
        Map<ItemType, Long> playerCooldowns = until.get(player);
        if (playerCooldowns == null) {
            return 0L;
        }
        long remaining = playerCooldowns.getOrDefault(type, 0L) - System.currentTimeMillis();
        if (remaining <= 0L) {
            playerCooldowns.remove(type);
            if (playerCooldowns.isEmpty()) {
                until.remove(player);
            }
            return 0L;
        }
        return remaining;
    }

    public void start(UUID player, ItemType type, long millis) {
        if (millis <= 0L) {
            return;
        }
        until.computeIfAbsent(player, key -> new EnumMap<>(ItemType.class))
                .put(type, System.currentTimeMillis() + millis);
    }

    public void clear(UUID player) {
        until.remove(player);
    }
}
