package gg.guab.gambling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetMathTest {

    @Test
    void betsMustBePositiveAndInsideTheTableLimits() {
        assertFalse(BetMath.validBet(0, 10, -1));
        assertFalse(BetMath.validBet(-5, 10, -1));
        assertFalse(BetMath.validBet(9, 10, -1));
        assertTrue(BetMath.validBet(10, 10, -1));
        assertTrue(BetMath.validBet(500, 10, -1));
        assertTrue(BetMath.validBet(50, 10, 100));
        assertFalse(BetMath.validBet(101, 10, 100));
    }

    @Test
    void minimumNeverDropsBelowOne() {
        assertFalse(BetMath.validBet(0, 0, -1));
        assertTrue(BetMath.validBet(1, 0, -1));
    }

    @Test
    void doublingIsRefusedWhenTheStakeWouldOverflow() {
        assertFalse(BetMath.canDouble(0));
        assertFalse(BetMath.canDouble(-1));
        assertTrue(BetMath.canDouble(Integer.MAX_VALUE / 2));
        assertFalse(BetMath.canDouble(Integer.MAX_VALUE / 2 + 1));
        assertFalse(BetMath.canDouble(Integer.MAX_VALUE));
    }

    @Test
    void payoutsClampToTheLedgerIntegerRange() {
        assertEquals(0, BetMath.payout(-50));
        assertEquals(0, BetMath.payout(0));
        assertEquals(40, BetMath.payout(40));
        assertEquals(Integer.MAX_VALUE, BetMath.payout(Long.MAX_VALUE));
    }

    @Test
    void winPayoutReturnsDoubleTheStakeAndClamps() {
        assertEquals(20, BetMath.winPayout(10));
        assertEquals(Integer.MAX_VALUE, BetMath.winPayout(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE - 1, BetMath.winPayout(Integer.MAX_VALUE / 2));
    }

    @Test
    void naturalPayoutAddsTheConfiguredBonus() {
        assertEquals(25, BetMath.naturalPayout(10, 1.5));
        assertEquals(10, BetMath.naturalPayout(10, 0.0));
        assertEquals(Integer.MAX_VALUE, BetMath.naturalPayout(Integer.MAX_VALUE, 1.5));
    }
}
