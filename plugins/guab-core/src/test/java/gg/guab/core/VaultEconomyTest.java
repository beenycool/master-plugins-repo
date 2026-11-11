package gg.guab.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VaultEconomyTest {

    @Test
    void keepsLargeAmountsAsSixtyFourBitValues() {
        assertEquals(2_147_483_648L, VaultEconomy.toPoints(2_147_483_648.0));
        assertEquals(4_294_967_297L, VaultEconomy.toPoints(4_294_967_297.0));
        assertEquals(9_000_000_000_000_000L, VaultEconomy.toPoints(9_000_000_000_000_000.0));
        assertEquals(2_147_483_646L, VaultEconomy.toPoints(2_147_483_646.0));
    }

    @Test
    void rejectsNonPositiveAndNonFiniteAmounts() {
        assertEquals(0L, VaultEconomy.toPoints(0.0));
        assertEquals(0L, VaultEconomy.toPoints(-1.0));
        assertEquals(0L, VaultEconomy.toPoints(Double.NaN));
        assertEquals(0L, VaultEconomy.toPoints(Double.POSITIVE_INFINITY));
        assertEquals(0L, VaultEconomy.toPoints(Double.NEGATIVE_INFINITY));
    }

    @Test
    void roundsNormalAmounts() {
        assertEquals(1L, VaultEconomy.toPoints(1.4));
        assertEquals(2L, VaultEconomy.toPoints(1.5));
        assertEquals(100L, VaultEconomy.toPoints(99.6));
    }
}
