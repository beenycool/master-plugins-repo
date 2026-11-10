package gg.guab.minigames;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Crash-safe storage for the inventories captured when a player joins the pit.
 * Every mutation is flushed to {@code saved-inventories.yml} with a temp file and
 * an atomic move, so a server crash can never leave a player without their gear.
 */
final class SavedInventoryStore {

    private static final int CONTENTS_SIZE = 36;
    private static final int ARMOR_SIZE = 4;

    private final File file;
    private final Map<UUID, SavedInventory> entries = new LinkedHashMap<>();
    private final Object writeLock = new Object();
    private final java.util.logging.Logger logger;

    SavedInventoryStore(File file, java.util.logging.Logger logger) {
        this.file = file;
        this.logger = logger;
        load();
    }

    SavedInventory get(UUID id) {
        return entries.get(id);
    }

    boolean contains(UUID id) {
        return entries.containsKey(id);
    }

    int size() {
        return entries.size();
    }

    void put(UUID id, SavedInventory inventory) {
        entries.put(id, inventory.copy());
        save();
    }

    SavedInventory remove(UUID id) {
        SavedInventory removed = entries.remove(id);
        if (removed != null) {
            save();
        }
        return removed;
    }

    private void load() {
        entries.clear();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            if (file.length() > 0) {
                logger.warning("saved-inventories.yml has no players section; keeping it untouched.");
            }
            return;
        }
        for (String key : players.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException exception) {
                logger.warning("Skipping saved inventory with invalid player id '" + key + "'.");
                continue;
            }
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            try {
                ItemStack[] contents = readItems(section, "contents", CONTENTS_SIZE);
                ItemStack[] armor = readItems(section, "armor", ARMOR_SIZE);
                entries.put(id, new SavedInventory(contents, armor,
                        section.getItemStack("offhand"),
                        section.getItemStack("cursor"),
                        Math.max(0, section.getInt("held-slot", 0)),
                        Math.max(0, section.getInt("level", 0)),
                        Math.max(0.0f, (float) section.getDouble("exp", 0.0))));
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Skipping unreadable saved inventory for " + key + ".", exception);
            }
        }
    }

    private static ItemStack[] readItems(ConfigurationSection section, String path, int fallbackSize) {
        ConfigurationSection items = section.getConfigurationSection(path);
        int size = fallbackSize;
        if (items != null) {
            for (String key : items.getKeys(false)) {
                try {
                    int index = Integer.parseInt(key);
                    if (index >= 0 && index < 128) {
                        size = Math.max(size, index + 1);
                    }
                } catch (NumberFormatException ignored) {
                    // Unknown key; the fixed fallback size still covers normal inventories.
                }
            }
        }
        ItemStack[] result = new ItemStack[size];
        if (items != null) {
            for (int index = 0; index < size; index++) {
                result[index] = items.getItemStack(String.valueOf(index));
            }
        }
        return result;
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, SavedInventory> entry : entries.entrySet()) {
            SavedInventory inventory = entry.getValue();
            String base = "players." + entry.getKey();
            writeItems(yaml, base + ".contents", inventory.contents());
            writeItems(yaml, base + ".armor", inventory.armor());
            if (inventory.offHand() != null) {
                yaml.set(base + ".offhand", inventory.offHand());
            }
            if (inventory.cursor() != null) {
                yaml.set(base + ".cursor", inventory.cursor());
            }
            yaml.set(base + ".held-slot", inventory.heldSlot());
            yaml.set(base + ".level", inventory.level());
            yaml.set(base + ".exp", inventory.exp());
        }
        synchronized (writeLock) {
            try {
                Path path = file.toPath();
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Path temp = path.resolveSibling(path.getFileName() + ".tmp");
                Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                logger.log(Level.WARNING, "Could not save saved-inventories.yml.", exception);
            }
        }
    }

    private static void writeItems(YamlConfiguration yaml, String base, ItemStack[] items) {
        for (int index = 0; index < items.length; index++) {
            if (items[index] != null) {
                yaml.set(base + "." + index, items[index]);
            }
        }
    }
}
