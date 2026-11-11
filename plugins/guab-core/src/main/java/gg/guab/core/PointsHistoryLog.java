package gg.guab.core;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory recent-change log for {@code /points history}. Kept separate so
 * ordering and trimming are easy to unit-test without a running server.
 */
final class PointsHistoryLog {

    static final int DEFAULT_LIMIT = 12;

    record Change(long delta, long before, long after, Instant at) {
    }

    private final int limit;
    private final Map<UUID, Deque<Change>> histories = new ConcurrentHashMap<>();

    PointsHistoryLog() {
        this(DEFAULT_LIMIT);
    }

    PointsHistoryLog(int limit) {
        this.limit = Math.max(1, limit);
    }

    void record(UUID player, long before, long after, Instant at) {
        if (player == null || before == after) {
            return;
        }
        Deque<Change> changes = histories.computeIfAbsent(player,
                key -> new ArrayDeque<>());
        synchronized (changes) {
            changes.addFirst(new Change(after - before, before, after, at));
            while (changes.size() > limit) {
                changes.removeLast();
            }
        }
    }

    /** Newest first. */
    List<Change> recent(UUID player) {
        Deque<Change> changes = histories.get(player);
        if (changes == null) {
            return List.of();
        }
        synchronized (changes) {
            return new ArrayList<>(changes);
        }
    }

    void clear(UUID player) {
        histories.remove(player);
    }
}
