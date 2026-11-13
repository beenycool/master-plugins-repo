package gg.guab.marriage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarriageStoreTest {

    private static final UUID LOW = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HIGH = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    private static MarriageRecord record() {
        return new MarriageRecord(HIGH, LOW, "High", "Low", 1_600_000_000_000L, true, null);
    }

    @Test
    void roundTripsACoupleWithAHome(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("marriages.yml");
        MarriageStore store = new MarriageStore(file);
        MarriageRecord.Home home = new MarriageRecord.Home("world_nether", 12.5, 64.0, -3.25, 90.0f, -10.0f);

        store.put(new MarriageRecord(HIGH, LOW, "High", "Low", 1_600_000_000_000L, false, home));

        assertTrue(Files.isRegularFile(file));
        YamlConfiguration raw = YamlConfiguration.loadConfiguration(file.toFile());
        assertTrue(raw.contains("couples." + MarriageRecord.key(LOW, HIGH)));

        MarriageStore reloaded = new MarriageStore(file);
        MarriageRecord loaded = reloaded.find(LOW);
        assertNotNull(loaded);
        // The record canonicalises the pair, so LOW is always first.
        assertEquals(LOW, loaded.first());
        assertEquals(HIGH, loaded.second());
        assertEquals("Low", loaded.firstName());
        assertEquals("High", loaded.secondName());
        assertEquals(1_600_000_000_000L, loaded.since());
        assertFalse(loaded.pvp());
        assertNotNull(loaded.home());
        assertEquals("world_nether", loaded.home().world());
        assertEquals(12.5, loaded.home().x(), 0.0001);
        assertEquals(64.0, loaded.home().y(), 0.0001);
        assertEquals(-3.25, loaded.home().z(), 0.0001);
        assertEquals(90.0f, loaded.home().yaw(), 0.0001f);
        assertEquals(-10.0f, loaded.home().pitch(), 0.0001f);
    }

    @Test
    void sortedPairKeyIsIndependentOfArgumentOrder() {
        assertEquals(MarriageRecord.key(LOW, HIGH), MarriageRecord.key(HIGH, LOW));

        MarriageRecord record = record();
        assertEquals(LOW, record.first());
        assertEquals(HIGH, record.second());
        assertEquals("Low", record.firstName());
        assertEquals("High", record.secondName());
        assertEquals(MarriageRecord.key(LOW, HIGH), record.key());
    }

    @Test
    void malformedEntriesAreSkippedButValidOnesLoad(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("marriages.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        String base = "couples." + MarriageRecord.key(LOW, HIGH);
        yaml.set(base + ".first-name", "Alice");
        yaml.set(base + ".second-name", "Bob");
        yaml.set(base + ".since", 1234);
        yaml.set(base + ".pvp", false);
        yaml.set("couples.not-a-pair.first-name", "Nobody");
        yaml.set("couples.bad|uuid.first-name", "Broken");
        yaml.set("couples.scalar-entry", "not a section");
        Files.writeString(file, yaml.saveToString());

        MarriageStore store = new MarriageStore(file);

        assertEquals(1, store.size());
        MarriageRecord loaded = store.find(LOW);
        assertNotNull(loaded);
        assertEquals("Alice", loaded.firstName());
        assertEquals("Bob", loaded.secondName());
        assertFalse(loaded.pvp());
    }

    @Test
    void duplicatePutReplacesAndRemovalIsPersisted(@TempDir Path temp) {
        Path file = temp.resolve("marriages.yml");
        MarriageStore store = new MarriageStore(file);

        store.put(record());
        store.put(record());
        assertEquals(1, store.size());

        store.put(record().withPvp(false));
        assertEquals(1, store.size());
        assertFalse(store.find(LOW).pvp());

        assertNotNull(store.remove(LOW));
        assertEquals(0, store.size());
        assertEquals(0, new MarriageStore(file).size());
    }

    @Test
    void missingFileIsEmptyAndMalformedReloadKeepsMemory(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("marriages.yml");
        MarriageStore store = new MarriageStore(file);
        assertEquals(0, store.size());
        assertFalse(store.reload());
        assertEquals(0, store.size());

        store.put(record());
        assertEquals(1, store.size());

        Files.writeString(file, "couples: not-a-section\n");
        assertFalse(store.reload());
        assertEquals(1, store.size());
        assertNotNull(store.find(LOW));
    }
}
