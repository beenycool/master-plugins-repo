package gg.guab.items;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DartsSlotsTest {

    @Test
    void aMatchNeedsTwoFreePlaces() {
        assertTrue(DartsSlots.canStartMatch(0));
        assertTrue(DartsSlots.canStartMatch(2));
        assertFalse(DartsSlots.canStartMatch(3));
        assertFalse(DartsSlots.canStartMatch(4));
    }

    @Test
    void aSoloLegNeedsOneFreePlace() {
        assertTrue(DartsSlots.canStartSingle(3));
        assertFalse(DartsSlots.canStartSingle(4));
    }

    @Test
    void nextFreeSlotPicksTheLowestUnusedOche() {
        assertEquals(0, DartsSlots.nextFreeSlot(Set.of(), 4));
        assertEquals(1, DartsSlots.nextFreeSlot(Set.of(0), 4));
        assertEquals(2, DartsSlots.nextFreeSlot(List.of(0, 1, 3), 4));
        assertEquals(3, DartsSlots.nextFreeSlot(List.of(0, 1, 2), 4));
        // Full room falls back to slot 0 rather than throwing.
        assertEquals(0, DartsSlots.nextFreeSlot(List.of(0, 1, 2, 3), 4));
    }
}
