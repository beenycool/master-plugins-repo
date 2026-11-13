package gg.guab.minigames;

import gg.guab.core.YamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Career battle pit records plus the active bounty ledger. Kept separate from the live
 * {@link BattlePit.SessionStats} so a restart cannot lose a kill, a death or a best streak.
 * Everything is written atomically to {@code pit-stats.yml}.
 */
public final class PitStatsStore {

    private final GuabMinigamesPlugin plugin;
    private final File file;
    private final Map<UUID, Career> players = new HashMap<>();
    private final Map<UUID, Bounty> bounties = new HashMap<>();
    private boolean dirty;

    public PitStatsStore(GuabMinigamesPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pit-stats.yml");
    }

    public void load() {
        players.clear();
        bounties.clear();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section != null) {
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
        ConfigurationSection bountySection = yaml.getConfigurationSection("bounties");
        if (bountySection != null) {
            for (String key : bountySection.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    ConfigurationSection entry = bountySection.getConfigurationSection(key);
                    if (entry == null) {
                        continue;
                    }
                    long amount = entry.getLong("amount", 0L);
                    if (amount <= 0) {
                        continue;
                    }
                    Bounty bounty = new Bounty(id);
                    bounty.name = entry.getString("name", "unknown");
                    bounty.amount = amount;
                    bounties.put(id, bounty);
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed UUIDs instead of failing startup.
                }
            }
        }
    }

    private static Career read(ConfigurationSection entry) {
        Career career = new Career();
        career.name = entry.getString("name", "unknown");
        career.kills = entry.getInt("kills", 0);
        career.deaths = entry.getInt("deaths", 0);
        career.bestStreak = entry.getInt("best-streak", 0);
        career.lastPlayed = entry.getLong("last-played", 0L);
        return career;
    }

    /** A live career record, or {@code null} when the player has never fought. */
    public Career find(UUID player) {
        return players.get(player);
    }

    /** Case-insensitive career lookup by name, including offline players loaded from disk. */
    public Career findByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Career career : players.values()) {
            if (name.equalsIgnoreCase(career.name)) {
                return career;
            }
        }
        return null;
    }

    public Career career(UUID player, String name) {
        Career career = players.computeIfAbsent(player, ignored -> new Career());
        if (name != null && !name.isBlank()) {
            career.name = name;
        }
        return career;
    }

    public void recordKill(UUID player, String name, int streak) {
        Career career = career(player, name);
        career.kills++;
        if (streak > career.bestStreak) {
            career.bestStreak = streak;
        }
        career.lastPlayed = System.currentTimeMillis();
        dirty = true;
    }

    public void recordDeath(UUID player, String name) {
        Career career = career(player, name);
        career.deaths++;
        career.lastPlayed = System.currentTimeMillis();
        dirty = true;
    }

    /** Raises the career best streak when a live streak beats it. */
    public void recordStreak(UUID player, String name, int streak) {
        if (streak <= 0) {
            return;
        }
        Career career = career(player, name);
        if (streak > career.bestStreak) {
            career.bestStreak = streak;
            career.lastPlayed = System.currentTimeMillis();
            dirty = true;
        }
    }

    /** Career leaderboard, newest values first. */
    public List<Career> leaderboard(String metric, int limit) {
        Comparator<Career> comparator = switch (metric == null ? "" : metric.toLowerCase(Locale.ROOT)) {
            case "deaths" -> Comparator.comparingInt(Career::deaths).reversed();
            case "streak" -> Comparator.comparingInt(Career::bestStreak).reversed();
            default -> Comparator.comparingInt(Career::kills).reversed();
        };
        return players.values().stream()
                .filter(Career::hasPlayed)
                .sorted(comparator.thenComparing(Career::name, String.CASE_INSENSITIVE_ORDER))
                .limit(Math.max(1, limit))
                .toList();
    }

    /** Active bounty placed on a target, or {@code null}. */
    public Bounty bounty(UUID target) {
        return bounties.get(target);
    }

    public long bountyAmount(UUID target) {
        Bounty bounty = bounties.get(target);
        return bounty == null ? 0L : bounty.amount;
    }

    /** Adds to the target's existing bounty and returns the new total. */
    public long addBounty(UUID target, String name, long amount) {
        Bounty bounty = bounties.computeIfAbsent(target, Bounty::new);
        if (name != null && !name.isBlank()) {
            bounty.name = name;
        }
        bounty.amount = Math.max(0L, bounty.amount + Math.max(0L, amount));
        dirty = true;
        return bounty.amount;
    }

    /** Removes and returns the target's bounty so the killer can be paid exactly once. */
    public Bounty claimBounty(UUID target) {
        Bounty bounty = bounties.remove(target);
        if (bounty != null) {
            dirty = true;
        }
        return bounty;
    }

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Career> entry : players.entrySet()) {
            String path = "players." + entry.getKey();
            Career career = entry.getValue();
            yaml.set(path + ".name", career.name);
            yaml.set(path + ".kills", career.kills);
            yaml.set(path + ".deaths", career.deaths);
            yaml.set(path + ".best-streak", career.bestStreak);
            yaml.set(path + ".last-played", career.lastPlayed);
        }
        for (Map.Entry<UUID, Bounty> entry : bounties.entrySet()) {
            String path = "bounties." + entry.getKey();
            Bounty bounty = entry.getValue();
            yaml.set(path + ".name", bounty.name);
            yaml.set(path + ".amount", bounty.amount);
        }
        try {
            YamlFiles.writeAtomic(file.toPath(), yaml);
            dirty = false;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save pit-stats.yml: " + exception.getMessage());
        }
    }

    public static final class Career {

        private String name = "unknown";
        private int kills;
        private int deaths;
        private int bestStreak;
        private long lastPlayed;

        public String name() {
            return name;
        }

        public int kills() {
            return kills;
        }

        public int deaths() {
            return deaths;
        }

        public int bestStreak() {
            return bestStreak;
        }

        public boolean hasPlayed() {
            return kills > 0 || deaths > 0 || bestStreak > 0;
        }

        public String kd() {
            if (deaths == 0) {
                return kills == 0 ? "0.00" : String.format(Locale.ROOT, "%.2f", (double) kills);
            }
            return String.format(Locale.ROOT, "%.2f", (double) kills / deaths);
        }
    }

    public static final class Bounty {

        private final UUID target;
        private String name = "unknown";
        private long amount;

        Bounty(UUID target) {
            this.target = target;
        }

        public UUID target() {
            return target;
        }

        public String name() {
            return name;
        }

        public long amount() {
            return amount;
        }
    }
}
