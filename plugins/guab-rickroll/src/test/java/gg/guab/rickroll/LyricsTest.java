package gg.guab.rickroll;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LyricsTest {

    private static Lyrics load(String text) {
        return Lyrics.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesAndSortsLrcLines() {
        Lyrics lyrics = load("""
                [00:19.00] Never gonna give you up
                [00:21.50] Never gonna let you down
                [00:20.00] Never gonna run around
                not a timestamp
                [00:22.00]
                """);
        assertEquals(3, lyrics.size());
        assertNull(lyrics.lineAt(18.9));
        assertEquals("Never gonna give you up", lyrics.lineAt(19.0));
        assertEquals("Never gonna run around", lyrics.lineAt(20.5));
        assertEquals("Never gonna let you down", lyrics.lineAt(30.0));
    }

    @Test
    void missingInputIsEmpty() {
        Lyrics lyrics = Lyrics.load(null);
        assertEquals(0, lyrics.size());
        assertNull(lyrics.lineAt(100.0));
    }

    @Test
    void ignoresMalformedLines() {
        Lyrics lyrics = load("[bad] line\n[00:00.00] first\n[99:99.99] last\n");
        assertEquals(1, lyrics.size());
        assertEquals("first", lyrics.lineAt(1.0));
    }
}
