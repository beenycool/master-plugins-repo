package gg.guab.marriage;

import gg.guab.core.YamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * marriages.yml persistence. The store is intentionally independent of the
 * JavaPlugin so tests can point it at a temporary path.
 *
 * <p>Couples are keyed by the two sorted UUID strings joined with {@code |}.
 * Malformed entries are skipped with a warning; a malformed root keeps the
 * in-memory couples instead of wiping them.</p>
 */
public final class MarriageStore {

    private enum Status {
        OK, MISSING, MALFORMED
    }

    private record LoadResult(Status status, Map<String, MarriageRecord> records) {
    }

    private final Path file;
    private final Logger logger;
    private final Map<String, MarriageRecord> couples = new LinkedHashMap<>();
    private final Object ioLock = new Object();

    public MarriageStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger == null ? Logger.getLogger("GuabMarriage") : logger;
        LoadResult result = parseFile();
        if (result.status() == Status.OK) {
            couples.putAll(result.records());
        } else if (result.status() == Status.MALFORMED) {
            warn("marriages.yml is malformed; starting with no couples in memory.");
        }
    }

    public MarriageStore(Path file) {
        this(file, Logger.getLogger("GuabMarriage"));
    }

    public Path file() {
        return file;
    }

    public synchronized MarriageRecord find(UUID player) {
        if (player == null) {
            return null;
        }
        for (MarriageRecord record : couples.values()) {
            if (record.involves(player)) {
                return record;
            }
        }
        return null;
    }

    public synchronized MarriageRecord find(UUID one, UUID two) {
        if (one == null || two == null) {
            return null;
        }
        return couples.get(MarriageRecord.key(one, two));
    }

    /** Last-known name lookup, used by {@code /marry admin divorce <player>}. */
    public synchronized MarriageRecord findByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.trim();
        for (MarriageRecord record : couples.values()) {
            if (record.firstName().equalsIgnoreCase(wanted)
                    || record.secondName().equalsIgnoreCase(wanted)) {
                return record;
            }
        }
        return null;
    }

    public synchronized List<MarriageRecord> all() {
        return List.copyOf(couples.values());
    }

    public synchronized int size() {
        return couples.size();
    }

    /**
     * Adds or replaces a couple. Any previous marriage involving either player
     * is removed first, so one player can never accidentally own two records.
     */
    public void put(MarriageRecord record) {
        if (record == null) {
            return;
        }
        synchronized (this) {
            couples.entrySet().removeIf(entry -> !entry.getKey().equals(record.key())
                    && (entry.getValue().involves(record.first())
                    || entry.getValue().involves(record.second())));
            couples.put(record.key(), record);
        }
        save();
    }

    public MarriageRecord remove(UUID player) {
        MarriageRecord removed;
        synchronized (this) {
            removed = find(player);
            if (removed != null) {
                couples.remove(removed.key());
            }
        }
        if (removed != null) {
            save();
        }
        return removed;
    }

    public MarriageRecord remove(UUID one, UUID two) {
        MarriageRecord removed;
        synchronized (this) {
            removed = couples.remove(MarriageRecord.key(one, two));
        }
        if (removed != null) {
            save();
        }
        return removed;
    }

    /** Reloads from disk. Missing or malformed files keep the in-memory data. */
    public boolean reload() {
        LoadResult result = parseFile();
        if (result.status() != Status.OK) {
            warn("marriages.yml is missing or malformed; keeping " + size()
                    + " couple(s) in memory instead of wiping them.");
            return false;
        }
        synchronized (this) {
            couples.clear();
            couples.putAll(result.records());
        }
        return true;
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<MarriageRecord> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(couples.values());
        }
        for (MarriageRecord record : snapshot) {
            String base = "couples." + record.key();
            yaml.set(base + ".first", record.first().toString());
            yaml.set(base + ".second", record.second().toString());
            yaml.set(base + ".first-name", record.firstName());
            yaml.set(base + ".second-name", record.secondName());
            yaml.set(base + ".since", record.since());
            yaml.set(base + ".pvp", record.pvp());
            MarriageRecord.Home home = record.home();
            if (home != null) {
                yaml.set(base + ".home.world", home.world());
                yaml.set(base + ".home.x", home.x());
                yaml.set(base + ".home.y", home.y());
                yaml.set(base + ".home.z", home.z());
                yaml.set(base + ".home.yaw", home.yaw());
                yaml.set(base + ".home.pitch", home.pitch());
            }
        }
        synchronized (ioLock) {
            try {
                YamlFiles.writeAtomic(file, yaml);
            } catch (IOException exception) {
                warn("Could not save marriages.yml: " + exception.getMessage());
            }
        }
    }

    private LoadResult parseFile() {
        if (!Files.isRegularFile(file)) {
            return new LoadResult(Status.MISSING, Map.of());
        }
        YamlConfiguration yaml;
        try {
            yaml = YamlConfiguration.loadConfiguration(file.toFile());
        } catch (RuntimeException exception) {
            warn("Could not read marriages.yml: " + exception.getMessage());
            return new LoadResult(Status.MALFORMED, Map.of());
        }
        ConfigurationSection root = yaml.getConfigurationSection("couples");
        if (root == null) {
            Object raw = yaml.get("couples");
            if (raw == null) {
                if (yaml.contains("couples")) {
                    return new LoadResult(Status.OK, Map.of());
                }
                if (file.toFile().length() > 0L) {
                    warn("marriages.yml has no 'couples' section.");
                    return new LoadResult(Status.MALFORMED, Map.of());
                }
                return new LoadResult(Status.OK, Map.of());
            }
            if (raw instanceof ConfigurationSection || raw instanceof Map<?, ?>) {
                return new LoadResult(Status.OK, Map.of());
            }
            warn("marriages.yml has a malformed 'couples' value.");
            return new LoadResult(Status.MALFORMED, Map.of());
        }

        Map<String, MarriageRecord> loaded = new LinkedHashMap<>();
        for (String key : root.getKeys(false)) {
            MarriageRecord record = parseEntry(key, root.getConfigurationSection(key), logger);
            if (record != null) {
                loaded.put(record.key(), record);
            }
        }
        return new LoadResult(Status.OK, loaded);
    }

    /**
     * Parses one entry. Visible for tests; the key is authoritative for which
     * two UUIDs are married, while names and state come from the section.
     */
    public static MarriageRecord parseEntry(String pairKey, ConfigurationSection entry, Logger logger) {
        String[] parts = pairKey == null ? new String[0] : pairKey.split("\\|", -1);
        if (parts.length != 2) {
            warn(logger, "Skipping malformed marriage key '" + pairKey + "' in marriages.yml.");
            return null;
        }
        if (entry == null) {
            warn(logger, "Skipping marriage '" + pairKey + "' with no settings in marriages.yml.");
            return null;
        }
        UUID first = uuid(parts[0]);
        UUID second = uuid(parts[1]);
        if (first == null || second == null || first.equals(second)) {
            warn(logger, "Skipping marriage '" + pairKey + "' with invalid player ids in marriages.yml.");
            return null;
        }
        try {
            String firstName = entry.getString("first-name", "");
            String secondName = entry.getString("second-name", "");
            long since = Math.max(0L, entry.getLong("since", 0L));
            boolean pvp = entry.getBoolean("pvp", true);
            MarriageRecord.Home home = parseHome(entry.getConfigurationSection("home"), pairKey, logger);
            return new MarriageRecord(first, second, firstName, secondName, since, pvp, home);
        } catch (RuntimeException exception) {
            warn(logger, "Skipping marriage '" + pairKey + "' with invalid data: " + exception.getMessage());
            return null;
        }
    }

    private static MarriageRecord.Home parseHome(ConfigurationSection home, String pairKey, Logger logger) {
        if (home == null) {
            return null;
        }
        String world = home.getString("world", "");
        if (world == null || world.isBlank()) {
            warn(logger, "Marriage '" + pairKey + "' has a home without a world; ignoring the home.");
            return null;
        }
        try {
            double x = home.getDouble("x");
            double y = home.getDouble("y");
            double z = home.getDouble("z");
            float yaw = (float) home.getDouble("yaw", 0.0);
            float pitch = (float) home.getDouble("pitch", 0.0);
            return new MarriageRecord.Home(world, x, y, z, yaw, pitch);
        } catch (RuntimeException exception) {
            warn(logger, "Marriage '" + pairKey + "' has invalid home coordinates; ignoring the home.");
            return null;
        }
    }

    private static UUID uuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void warn(String message) {
        warn(logger, message);
    }

    private static void warn(Logger logger, String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }
}
