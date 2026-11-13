package gg.guab.items;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Career darts records. Kept deliberately separate from the live leg state so a
 * restart can never lose a completed leg or a 180.
 */
public final class DartsStatsStore {

    private final GuabItemsPlugin plugin;
    private final File file;
    private final Map<UUID, Stats> players = new HashMap<>();
    private boolean dirty;

    public DartsStatsStore(GuabItemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "darts.yml");
    }

    public void load() {
        players.clear();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) {
                    continue;
                }
                players.put(id, read(entry));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed UUIDs instead of failing startup.
            }
        }
    }

    private static Stats read(ConfigurationSection entry) {
        Stats stats = new Stats();
        stats.name = entry.getString("name", "unknown");
        stats.legsPlayed = entry.getInt("legs-played", 0);
        stats.legsWon = entry.getInt("legs-won", 0);
        stats.dartsThrown = entry.getInt("darts-thrown", 0);
        stats.bestLegDarts = entry.getInt("best-leg-darts", 0);
        stats.oneEighties = entry.getInt("one-eighties", 0);
        stats.highestCheckout = entry.getInt("highest-checkout", 0);
        stats.checkouts = entry.getInt("checkouts", 0);
        stats.bestVisit = entry.getInt("best-visit", 0);
        stats.lastPlayed = entry.getLong("last-played", 0L);
        return stats;
    }

    public Stats stats(UUID player) {
        return players.computeIfAbsent(player, ignored -> new Stats());
    }

    /** A live career record, or null when the player has never thrown a dart. */
    public Stats find(UUID player) {
        return players.get(player);
    }

    /**
     * Public stats lookup by name. Checks the live cache first, then reads
     * darts.yml so offline players and console callers do not need a server
     * session or an admin permission.
     */
    public Stats findByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Stats stats : players.values()) {
            if (name.equalsIgnoreCase(stats.name)) {
                return stats;
            }
        }
        if (!file.isFile()) {
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            return null;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            if (name.equalsIgnoreCase(entry.getString("name", ""))) {
                return read(entry);
            }
        }
        return null;
    }

    public void recordLegStart(UUID player, String name) {
        Stats stats = stats(player);
        stats.name = name;
        stats.legsPlayed++;
        stats.lastPlayed = System.currentTimeMillis();
        dirty = true;
    }

    /**
     * Undo a leg start when the player never made it onto the oche (for example
     * the darts room could not be created).
     */
    public void rollbackLegStart(UUID player) {
        Stats stats = players.get(player);
        if (stats == null) {
            return;
        }
        stats.legsPlayed = Math.max(0, stats.legsPlayed - 1);
        dirty = true;
    }

    public void recordThrow(UUID player) {
        Stats stats = stats(player);
        stats.dartsThrown++;
        stats.lastPlayed = System.currentTimeMillis();
        dirty = true;
    }

    public void recordVisit(UUID player, int visit) {
        Stats stats = stats(player);
        stats.bestVisit = Math.max(stats.bestVisit, visit);
        dirty = true;
    }

    public void record180(UUID player) {
        Stats stats = stats(player);
        stats.oneEighties++;
        dirty = true;
    }

    public void recordCheckout(UUID player, int legDarts, int checkout) {
        Stats stats = stats(player);
        stats.legsWon++;
        stats.checkouts++;
        stats.highestCheckout = Math.max(stats.highestCheckout, checkout);
        if (stats.bestLegDarts == 0 || legDarts < stats.bestLegDarts) {
            stats.bestLegDarts = legDarts;
        }
        stats.lastPlayed = System.currentTimeMillis();
        dirty = true;
    }

    public void recordForfeitWin(UUID player) {
        Stats stats = stats(player);
        stats.legsWon++;
        stats.lastPlayed = System.currentTimeMillis();
        dirty = true;
    }

    public List<Map.Entry<String, Integer>> top(String metric, int limit) {
        List<Map.Entry<UUID, Stats>> entries = new ArrayList<>(players.entrySet());
        Comparator<Map.Entry<UUID, Stats>> comparator = switch (metric.toLowerCase()) {
            case "180s", "180", "ones" ->
                    Comparator.comparingInt((Map.Entry<UUID, Stats> entry) -> entry.getValue().oneEighties).reversed();
            case "checkout", "checkouts" ->
                    Comparator.comparingInt((Map.Entry<UUID, Stats> entry) -> entry.getValue().highestCheckout).reversed();
            case "best", "bestleg", "best-leg" ->
                    Comparator.comparingInt((Map.Entry<UUID, Stats> entry) -> {
                        int best = entry.getValue().bestLegDarts;
                        return best == 0 ? Integer.MAX_VALUE : best;
                    });
            case "darts", "thrown" ->
                    Comparator.comparingInt((Map.Entry<UUID, Stats> entry) -> entry.getValue().dartsThrown).reversed();
            default ->
                    Comparator.comparingInt((Map.Entry<UUID, Stats> entry) -> entry.getValue().legsWon).reversed();
        };
        comparator = comparator.thenComparing(entry -> entry.getValue().name, String.CASE_INSENSITIVE_ORDER);
        entries.sort(comparator);

        List<Map.Entry<String, Integer>> result = new ArrayList<>();
        for (Map.Entry<UUID, Stats> entry : entries) {
            Stats stats = entry.getValue();
            int value = switch (metric.toLowerCase()) {
                case "180s", "180", "ones" -> stats.oneEighties;
                case "checkout", "checkouts" -> stats.highestCheckout;
                case "best", "bestleg", "best-leg" -> stats.bestLegDarts;
                case "darts", "thrown" -> stats.dartsThrown;
                default -> stats.legsWon;
            };
            if (value <= 0) {
                continue;
            }
            result.add(Map.entry(stats.name, value));
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Stats> entry : players.entrySet()) {
            String path = "players." + entry.getKey();
            Stats stats = entry.getValue();
            yaml.set(path + ".name", stats.name);
            yaml.set(path + ".legs-played", stats.legsPlayed);
            yaml.set(path + ".legs-won", stats.legsWon);
            yaml.set(path + ".darts-thrown", stats.dartsThrown);
            yaml.set(path + ".best-leg-darts", stats.bestLegDarts);
            yaml.set(path + ".one-eighties", stats.oneEighties);
            yaml.set(path + ".highest-checkout", stats.highestCheckout);
            yaml.set(path + ".checkouts", stats.checkouts);
            yaml.set(path + ".best-visit", stats.bestVisit);
            yaml.set(path + ".last-played", stats.lastPlayed);
        }
        try {
            gg.guab.core.YamlFiles.writeAtomic(file.toPath(), yaml);
            dirty = false;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save darts.yml: " + exception.getMessage());
        }
    }

    public static final class Stats {
        private String name = "unknown";
        private int legsPlayed;
        private int legsWon;
        private int dartsThrown;
        private int bestLegDarts;
        private int oneEighties;
        private int highestCheckout;
        private int checkouts;
        private int bestVisit;
        private long lastPlayed;

        public String name() {
            return name;
        }

        public int legsPlayed() {
            return legsPlayed;
        }

        public int legsWon() {
            return legsWon;
        }

        public int dartsThrown() {
            return dartsThrown;
        }

        public int bestLegDarts() {
            return bestLegDarts;
        }

        public int oneEighties() {
            return oneEighties;
        }

        public int highestCheckout() {
            return highestCheckout;
        }

        public int checkouts() {
            return checkouts;
        }

        public int bestVisit() {
            return bestVisit;
        }
    }
}
