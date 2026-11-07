package gg.guab.items;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One-time quest claims, stored in {@code quests.yml}. The claim check and the
 * write are deliberately small and synchronous because they run on the main
 * thread from a dialog callback; that is what makes repeated clicks safe.
 */
public final class QuestsStore {

    private final GuabItemsPlugin plugin;
    private final File file;
    private final Map<UUID, Set<String>> claimed = new HashMap<>();

    public QuestsStore(GuabItemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "quests.yml");
    }

    public void start() {
        load();
    }

    public void stop() {
        save();
    }

    public boolean claimed(UUID player, String questId) {
        Set<String> quests = claimed.get(player);
        return quests != null && quests.contains(questId);
    }

    /** Returns true only for the first successful claim of this quest. */
    public synchronized boolean claim(UUID player, String questId) {
        Set<String> quests = claimed.computeIfAbsent(player, ignored -> new HashSet<>());
        if (!quests.add(questId)) {
            return false;
        }
        save();
        return true;
    }

    private void load() {
        claimed.clear();
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
                Set<String> quests = new HashSet<>();
                for (String questId : section.getStringList(key + ".claimed")) {
                    if (questId != null && !questId.isBlank()) {
                        quests.add(questId);
                    }
                }
                claimed.put(id, quests);
            } catch (IllegalArgumentException ignored) {
                // Skip malformed UUIDs rather than failing startup.
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> entry : claimed.entrySet()) {
            yaml.set("players." + entry.getKey() + ".claimed", new ArrayList<>(entry.getValue()));
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save quests.yml: " + exception.getMessage());
        }
    }
}
