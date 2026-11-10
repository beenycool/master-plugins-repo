package gg.guab.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HudTokensTest {

    @Test
    void replacesKnownTokensAndKeepsUnknownOnes() {
        HudTokens tokens = new HudTokens()
                .put("%points%", "1,234")
                .put("top1", "Alice 50");

        assertEquals("Points 1,234 · #1 Alice 50 · %online%",
                tokens.apply("Points %points% · #1 %top1% · %online%"));
    }

    @Test
    void nullAndEmptyLinesAreSafe() {
        HudTokens tokens = new HudTokens().put("%player%", "Alice");

        assertEquals("", tokens.apply(null));
        assertEquals("", tokens.apply(""));
    }

    @Test
    void duplicateTokenKeepsTheLatestValue() {
        HudTokens tokens = new HudTokens()
                .put("%points%", "1")
                .put("%points%", "2");

        assertEquals("2", tokens.apply("%points%"));
        assertEquals(1, tokens.size());
    }
}
