package gg.guab.minigames;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattlePitLogicTest {

    @Test
    void arenaIncludesTheExactRadiusAndRejectsJustOutside() {
        assertTrue(BattlePit.withinArena(0.0, 0.0, 0.0, 0.0, 24.0));
        assertTrue(BattlePit.withinArena(24.0, 0.0, 0.0, 0.0, 24.0));
        assertTrue(BattlePit.withinArena(0.0, -24.0, 0.0, 0.0, 24.0));
        assertFalse(BattlePit.withinArena(24.01, 0.0, 0.0, 0.0, 24.0));
        assertFalse(BattlePit.withinArena(17.0, 17.0, 0.0, 0.0, 24.0));
    }

    @Test
    void arenaRejectsInvalidCoordinates() {
        assertFalse(BattlePit.withinArena(Double.NaN, 0.0, 0.0, 0.0, 24.0));
        assertFalse(BattlePit.withinArena(0.0, 0.0, 0.0, 0.0, -1.0));
        assertFalse(BattlePit.withinArena(0.0, 0.0, 0.0, 0.0, Double.POSITIVE_INFINITY));
    }

    @Test
    void streakAnnouncementIsDisabledForZeroOrNegativeIntervals() {
        assertFalse(BattlePit.shouldAnnounceStreak(5, 0));
        assertFalse(BattlePit.shouldAnnounceStreak(5, -1));
        assertTrue(BattlePit.shouldAnnounceStreak(5, 5));
        assertTrue(BattlePit.shouldAnnounceStreak(10, 5));
        assertFalse(BattlePit.shouldAnnounceStreak(6, 5));
        assertFalse(BattlePit.shouldAnnounceStreak(0, 5));
    }
}
