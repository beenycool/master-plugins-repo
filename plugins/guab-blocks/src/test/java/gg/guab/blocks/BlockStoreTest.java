package gg.guab.blocks;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BlockStoreTest {

    @Test
    void parsesAValidBlockEntry() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("type", "fake");
        yaml.set("chance", 0.5);
        yaml.set("restore-delay-ticks", 40);
        yaml.set("cooldown-seconds", 12);
        yaml.set("break-sound", "block.note_block.hat");

        PrankBlock block = BlockStore.parseEntry("World;10;64;-5", yaml, null);

        assertNotNull(block);
        assertEquals("World", block.world());
        assertEquals(10, block.x());
        assertEquals(64, block.y());
        assertEquals(-5, block.z());
        assertEquals(PrankType.FAKE, block.type());
        assertEquals(0.5, block.chance(), 0.0001);
        assertEquals(40, block.restoreDelayTicks());
        assertEquals(12, block.cooldownSeconds());
        assertEquals("block.note_block.hat", block.breakSound());
        // World keys are normalized so a case change cannot create duplicates.
        assertEquals("world;10;64;-5", block.key());
    }

    @Test
    void malformedKeysAndTypesAreSkipped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("type", "fake");

        assertNull(BlockStore.parseEntry("not-a-coordinate", yaml, null));
        assertNull(BlockStore.parseEntry("world;1;2;3", null, null));

        yaml.set("type", "unknown");
        assertNull(BlockStore.parseEntry("world;1;2;3", yaml, null));

        yaml.set("type", "fake");
        assertNull(BlockStore.parseEntry("world;x;2;3", yaml, null));
    }

    @Test
    void chanceIsClampedToTheUnitRange() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("type", "fake");
        yaml.set("chance", 4.5);
        assertEquals(1.0, BlockStore.parseEntry("world;0;0;0", yaml, null).chance(), 0.0001);

        yaml.set("chance", -2.0);
        assertEquals(0.0, BlockStore.parseEntry("world;0;0;0", yaml, null).chance(), 0.0001);
    }

    @Test
    void soundKeysKeepTheirUnderscores() {
        assertEquals("minecraft:block.note_block.hat",
                BlocksConfig.soundKey("block.note_block.hat"));
        assertEquals("minecraft:block.glass.break",
                BlocksConfig.soundKey("block.glass.break"));
        assertEquals("custom:sound.key", BlocksConfig.soundKey("custom:sound.key"));
        assertFalse(BlocksConfig.soundKey("block.note_block.hat").contains("note.block"));
    }
}
