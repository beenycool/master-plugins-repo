package gg.guab.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PointsHistoryLogTest {

    @Test
    void newestChangesComeFirst() {
        PointsHistoryLog log = new PointsHistoryLog();
        UUID player = UUID.randomUUID();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");

        log.record(player, 0, 10, base);
        log.record(player, 10, 7, base.plusSeconds(1));
        log.record(player, 7, 27, base.plusSeconds(2));

        List<PointsHistoryLog.Change> recent = log.recent(player);
        assertEquals(3, recent.size());
        assertEquals(20L, recent.get(0).delta());
        assertEquals(-3L, recent.get(1).delta());
        assertEquals(10L, recent.get(2).delta());
        assertEquals(7L, recent.get(0).before());
        assertEquals(27L, recent.get(0).after());
    }

    @Test
    void zeroDeltaIsIgnoredAndOldEntriesAreTrimmed() {
        PointsHistoryLog log = new PointsHistoryLog(2);
        UUID player = UUID.randomUUID();
        Instant base = Instant.now();

        log.record(player, 5, 5, base);
        log.record(player, 0, 1, base);
        log.record(player, 1, 3, base.plusSeconds(1));
        log.record(player, 3, 6, base.plusSeconds(2));

        List<PointsHistoryLog.Change> recent = log.recent(player);
        assertEquals(2, recent.size());
        assertEquals(3L, recent.get(0).delta());
        assertEquals(2L, recent.get(1).delta());
    }
}
