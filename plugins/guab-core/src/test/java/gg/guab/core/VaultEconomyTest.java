package gg.guab.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VaultEconomyTest {

    @Test
    void saturatesLargeAmountsInsteadOfWrapping() {
        assertEquals(Integer.MAX_VALUE, VaultEconomy.toPoints(2_147_483_648.0));
        assertEquals(Integer.MAX_VALUE, VaultEconomy.toPoints(4_294_967_297.0));
        assertEquals(Integer.MAX_VALUE, VaultEconomy.toPoints(9_000_000_000_000_000.0));
        assertEquals(2_147_483_646, VaultEconomy.toPoints(2_147_483_646.0));
    }

    @Test
    void rejectsNonPositiveAndNonFiniteAmounts() {
        assertEquals(0, VaultEconomy.toPoints(0.0));
        assertEquals(0, VaultEconomy.toPoints(-1.0));
        assertEquals(0, VaultEconomy.toPoints(Double.NaN));
        assertEquals(0, VaultEconomy.toPoints(Double.POSITIVE_INFINITY));
        assertEquals(0, VaultEconomy.toPoints(Double.NEGATIVE_INFINITY));
    }

    @Test
    void roundsNormalAmounts() {
        assertEquals(1, VaultEconomy.toPoints(1.4));
        assertEquals(2, VaultEconomy.toPoints(1.5));
        assertEquals(100, VaultEconomy.toPoints(99.6));
    }
}
