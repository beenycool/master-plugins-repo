package gg.guab.marriage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarriageRecordTest {

    private static final long DAY = 86_400_000L;
    private static final UUID LOW = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HIGH = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Test
    void daysTogetherRoundsDownAndNeverGoesNegative() {
        MarriageRecord record = new MarriageRecord(LOW, HIGH, "Low", "High", 0L, true, null);

        assertEquals(0L, record.daysTogether(-5L));
        assertEquals(0L, record.daysTogether(DAY - 1L));
        assertEquals(1L, record.daysTogether(DAY));
        assertEquals(2L, record.daysTogether(2L * DAY + 500L));
    }

    @Test
    void daysLabelAndDateFormattingAreReadable() {
        MarriageRecord record = new MarriageRecord(LOW, HIGH, "Low", "High", 0L, true, null);
        assertEquals("1 day", record.daysLabel(DAY));
        assertEquals("3 days", record.daysLabel(3L * DAY));

        long epoch = Instant.parse("2020-01-01T12:00:00Z").toEpochMilli();
        assertEquals("1 Jan 2020", MarriageRecord.formatDate(epoch, ZoneOffset.UTC));
    }

    @Test
    void partnerLookupsUseTheCanonicalOrder() {
        MarriageRecord record = new MarriageRecord(HIGH, LOW, "High", "Low", 10L, true, null);

        assertTrue(record.involves(LOW));
        assertTrue(record.involves(HIGH));
        assertEquals(HIGH, record.other(LOW));
        assertEquals(LOW, record.other(HIGH));
        assertEquals("High", record.otherName(LOW));
        assertEquals("Low", record.otherName(HIGH));
        assertNull(record.other(UUID.randomUUID()));
        assertEquals("Low", record.nameOf(LOW));
    }
}
