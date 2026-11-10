package gg.guab.rickroll;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RickrollConfigTest {

    @Test
    void packHashParsesHex() {
        assertArrayEquals(new byte[] {0x00, 0x01, (byte) 0xab, (byte) 0xcd, (byte) 0xef},
                RickrollConfig.packHash("0001ABCDEF"));
    }

    @Test
    void blankOrInvalidHashFallsBackToEmpty() {
        assertEquals(0, RickrollConfig.packHash(null).length);
        assertEquals(0, RickrollConfig.packHash("").length);
        assertEquals(0, RickrollConfig.packHash("0").length);
        assertEquals(0, RickrollConfig.packHash("abc").length);
        assertEquals(0, RickrollConfig.packHash("zz").length);
        assertEquals(0, RickrollConfig.packHash("../etc").length);
    }

    @Test
    void packHashAcceptsWhitespace() {
        assertArrayEquals(new byte[] {(byte) 0xff, 0x00}, RickrollConfig.packHash("  ff00 \n"));
    }
}
