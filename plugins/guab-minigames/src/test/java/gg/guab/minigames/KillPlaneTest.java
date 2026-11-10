package gg.guab.minigames;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KillPlaneTest {

    @Test
    void onlyFinitelyLowPositionsAreKilled() {
        assertTrue(KillPlane.isBelow(-70.0, -66.0));
        assertTrue(KillPlane.isBelow(-66.01, -66.0));
        assertFalse(KillPlane.isBelow(-66.0, -66.0));
        assertFalse(KillPlane.isBelow(-60.0, -66.0));
    }

    @Test
    void malformedNumbersNeverTriggerThePlane() {
        assertFalse(KillPlane.isBelow(Double.NaN, -66.0));
        assertFalse(KillPlane.isBelow(-70.0, Double.NaN));
        assertFalse(KillPlane.isBelow(Double.NEGATIVE_INFINITY, -66.0));
        assertFalse(KillPlane.isBelow(-70.0, Double.POSITIVE_INFINITY));
    }
}
