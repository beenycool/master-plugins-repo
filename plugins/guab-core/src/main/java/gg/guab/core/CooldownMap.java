package gg.guab.core;

import java.util.HashMap;
import java.util.Map;

/**
 * A small, thread-unsafe cooldown table shared by the plugins. Entries are
 * stored as the millisecond timestamp at which the key becomes ready again and
 * are pruned lazily, so a busy key set never grows without bound.
 *
 * <p>Callers should use {@link #remainingMillis(Object)} for "wait another Xs"
 * messages and {@link #start(Object, long)} after the action succeeds.</p>
 */
public final class CooldownMap<K> {

    private final Map<K, Long> until = new HashMap<>();

    /** Milliseconds left before {@code key} is ready; zero when it is ready. */
    public long remainingMillis(K key) {
        Long readyAt = until.get(key);
        if (readyAt == null) {
            return 0L;
        }
        long remaining = readyAt - System.currentTimeMillis();
        if (remaining <= 0L) {
            until.remove(key);
            return 0L;
        }
        return remaining;
    }

    public boolean ready(K key) {
        return remainingMillis(key) <= 0L;
    }

    /** Starts (or restarts) a cooldown. Non-positive durations are ignored. */
    public void start(K key, long millis) {
        if (millis > 0L) {
            until.put(key, System.currentTimeMillis() + millis);
        }
    }

    /**
     * Starts the cooldown only when the key is ready.
     *
     * @return true when this call claimed the cooldown
     */
    public boolean startIfReady(K key, long millis) {
        if (remainingMillis(key) > 0L) {
            return false;
        }
        start(key, millis);
        return true;
    }

    public void clear(K key) {
        until.remove(key);
    }

    public void clearAll() {
        until.clear();
    }

    /** Drops expired entries so a large key population can be reclaimed. */
    public void removeExpired() {
        long now = System.currentTimeMillis();
        until.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    public int size() {
        return until.size();
    }
}
