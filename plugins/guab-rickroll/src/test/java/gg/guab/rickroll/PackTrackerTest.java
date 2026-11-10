package gg.guab.rickroll;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackTrackerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RICKROLL = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ITEMS = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void startsUnknown() {
        PackTracker tracker = new PackTracker();
        assertEquals(PackTracker.State.UNKNOWN, tracker.state(PLAYER));
        assertFalse(tracker.isReady(PLAYER));
        assertFalse(tracker.isDenied(PLAYER));
    }

    @Test
    void ignoresOtherPackStatuses() {
        PackTracker tracker = new PackTracker();
        tracker.recordRequest(PLAYER, RICKROLL, 100L);

        assertFalse(tracker.recordStatus(PLAYER, ITEMS, PackTracker.State.READY, 110L));
        assertEquals(PackTracker.State.REQUESTED, tracker.state(PLAYER));
        assertFalse(tracker.isReady(PLAYER));

        assertTrue(tracker.recordStatus(PLAYER, RICKROLL, PackTracker.State.READY, 120L));
        assertTrue(tracker.isReady(PLAYER));
    }

    @Test
    void downloadedCountsAsReady() {
        PackTracker tracker = new PackTracker();
        tracker.recordRequest(PLAYER, RICKROLL, 100L);
        assertTrue(tracker.recordStatus(PLAYER, RICKROLL, PackTracker.State.DOWNLOADED, 110L));
        assertTrue(tracker.isReady(PLAYER));
        assertEquals(PackTracker.State.DOWNLOADED, tracker.state(PLAYER));
    }

    @Test
    void denialIsResetByANewRequest() {
        PackTracker tracker = new PackTracker();
        tracker.recordRequest(PLAYER, RICKROLL, 100L);
        tracker.recordStatus(PLAYER, RICKROLL, PackTracker.State.DENIED, 110L);
        assertTrue(tracker.isDenied(PLAYER));

        tracker.recordRequest(PLAYER, RICKROLL, 120L);
        assertEquals(PackTracker.State.REQUESTED, tracker.state(PLAYER));
        assertFalse(tracker.isDenied(PLAYER));
        assertFalse(tracker.isReady(PLAYER));
    }

    @Test
    void forgetClearsState() {
        PackTracker tracker = new PackTracker();
        tracker.recordRequest(PLAYER, RICKROLL, 100L);
        tracker.recordStatus(PLAYER, RICKROLL, PackTracker.State.READY, 110L);
        tracker.forget(PLAYER);
        assertEquals(PackTracker.State.UNKNOWN, tracker.state(PLAYER));
    }
}
