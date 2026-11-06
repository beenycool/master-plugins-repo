package gg.guab.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** YAML-backed implementation of {@link StatsService}. */
public final class StatsStore implements StatsService {

    private final Path file;
    private final Map<UUID, PlayerStats> players = new LinkedHashMap<>();

    public StatsStore(Path file) {
        this.file = file;
    }

    public synchronized void load() {
        players.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException exception) {
                continue;
            }
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            PlayerStats stats = new PlayerStats(section.getString("name", key));
            for (String metric : METRICS) {
                int value = Math.max(0, section.getInt(metric, 0));
                if (value > 0) {
                    stats.counts.put(metric, value);
                }
            }
            players.put(uuid, stats);
        }
    }

    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerStats> entry : players.entrySet()) {
            String path = "players." + entry.getKey();
            PlayerStats stats = entry.getValue();
            yaml.set(path + ".name", stats.name);
            for (Map.Entry<String, Integer> count : stats.counts.entrySet()) {
                yaml.set(path + "." + count.getKey(), count.getValue());
            }
        }
        try {
            Files.createDirectories(file.getParent());
            yaml.save(file.toFile());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save " + file.getFileName(), exception);
        }
    }

    @Override
    public synchronized void increment(Player player, String metric, int amount) {
        if (amount == 0 || !METRICS.contains(metric)) {
            return;
        }
        PlayerStats stats = players.computeIfAbsent(player.getUniqueId(),
                id -> new PlayerStats(player.getName()));
        stats.name = player.getName();
        stats.counts.merge(metric, amount, Integer::sum);
        if (stats.counts.get(metric) < 0) {
            stats.counts.put(metric, 0);
        }
    }

    @Override
    public synchronized int get(UUID player, String metric) {
        PlayerStats stats = players.get(player);
        return stats == null ? 0 : stats.counts.getOrDefault(metric, 0);
    }

    @Override
    public synchronized String name(UUID player) {
        PlayerStats stats = players.get(player);
        return stats == null ? "unknown" : stats.name;
    }

    @Override
    public synchronized UUID find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String needle = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, PlayerStats> entry : players.entrySet()) {
            if (entry.getValue().name.toLowerCase(Locale.ROOT).equals(needle)) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Override
    public synchronized List<Entry> top(String metric, int limit) {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<UUID, PlayerStats> entry : players.entrySet()) {
            int value = entry.getValue().counts.getOrDefault(metric, 0);
            if (value > 0) {
                entries.add(new Entry(entry.getKey(), entry.getValue().name, value));
            }
        }
        entries.sort(Comparator.comparingInt(Entry::value).reversed()
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return entries.subList(0, Math.min(Math.max(0, limit), entries.size()));
    }

    private static final class PlayerStats {
        private String name;
        private final Map<String, Integer> counts = new HashMap<>();

        private PlayerStats(String name) {
            this.name = name;
        }
    }
}
