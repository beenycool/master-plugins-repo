package gg.guab.blocks;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class BlockStore {

    private final GuabBlocksPlugin plugin;
    private final File file;
    private final Map<String, PrankBlock> blocks = new LinkedHashMap<>();
    private final Object writeLock = new Object();

    public BlockStore(GuabBlocksPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "blocks.yml");
        Map<String, PrankBlock> loaded = parseFile();
        if (loaded != null) {
            blocks.putAll(loaded);
        }
    }

    public static String key(Block block) {
        return normalizeWorld(block.getWorld().getName()) + ";" + block.getX() + ";"
                + block.getY() + ";" + block.getZ();
    }

    static String normalizeWorld(String world) {
        return world == null ? "" : world.trim().toLowerCase(Locale.ROOT);
    }

    public PrankBlock get(Block block) {
        return blocks.get(key(block));
    }

    public PrankBlock get(String world, int x, int y, int z) {
        return blocks.get(coordinateKey(world, x, y, z));
    }

    public boolean contains(Block block) {
        return blocks.containsKey(key(block));
    }

    public boolean contains(String world, int x, int y, int z) {
        return blocks.containsKey(coordinateKey(world, x, y, z));
    }

    /**
     * Tags a block. Fake blocks are limited to simple solid blocks because the
     * trick works by replacing the block with air; containers, signs and other
     * tile entities would lose their contents.
     */
    public boolean add(Block block, PrankType type) {
        if (type == PrankType.FAKE && !isSimpleSolid(block)) {
            return false;
        }
        String key = key(block);
        PrankBlock previous = blocks.get(key);
        PrankBlock entry = previous != null
                ? previous.withType(type)
                : new PrankBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                        type, null, null, null, null, null, null, null);
        blocks.put(key, entry);
        save();
        return true;
    }

    public PrankBlock remove(Block block) {
        PrankBlock removed = blocks.remove(key(block));
        if (removed != null) {
            save();
        }
        return removed;
    }

    public PrankBlock removeAt(String world, int x, int y, int z) {
        PrankBlock removed = blocks.remove(coordinateKey(world, x, y, z));
        if (removed != null) {
            save();
        }
        return removed;
    }

    public void put(PrankBlock block) {
        blocks.put(block.key(), block);
        save();
    }

    public Collection<PrankBlock> all() {
        return List.copyOf(blocks.values());
    }

    public int size() {
        return blocks.size();
    }

    /**
     * Reloads from disk without risking data loss. A missing, unreadable or
     * malformed blocks.yml keeps the in-memory tags exactly as they were.
     */
    public boolean reload() {
        Map<String, PrankBlock> loaded = parseFile();
        if (loaded == null) {
            plugin.getLogger().warning("blocks.yml is missing or malformed; keeping "
                    + blocks.size() + " tagged block(s) in memory instead of wiping them.");
            return false;
        }
        blocks.clear();
        blocks.putAll(loaded);
        return true;
    }

    private Map<String, PrankBlock> parseFile() {
        if (!file.isFile()) {
            return null;
        }
        YamlConfiguration yaml;
        try {
            yaml = YamlConfiguration.loadConfiguration(file);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not read blocks.yml: " + exception.getMessage());
            return null;
        }
        ConfigurationSection root = yaml.getConfigurationSection("blocks");
        if (root == null) {
            Object rawBlocks = yaml.get("blocks");
            if (rawBlocks == null) {
                if (yaml.contains("blocks")) {
                    // An explicit, intentionally empty `blocks:` section means
                    // "no tags", not "malformed file".
                    return new LinkedHashMap<>();
                }
                if (file.length() > 0) {
                    plugin.getLogger().warning("blocks.yml has no 'blocks' section; keeping tags in memory.");
                    return null;
                }
                return new LinkedHashMap<>();
            }
            if (rawBlocks instanceof ConfigurationSection || rawBlocks instanceof Map<?, ?>) {
                // `blocks: {}` can deserialize as an empty section/map.
                return new LinkedHashMap<>();
            }
            plugin.getLogger().warning("blocks.yml has a malformed 'blocks' value; keeping tags in memory.");
            return null;
        }
        Map<String, PrankBlock> loaded = new LinkedHashMap<>();
        for (String key : root.getKeys(false)) {
            PrankBlock parsed = parseEntry(key, root.getConfigurationSection(key), plugin.getLogger());
            if (parsed != null) {
                loaded.put(parsed.key(), parsed);
            }
        }
        return loaded;
    }

    static PrankBlock parseEntry(String key, ConfigurationSection entry, Logger logger) {
        String[] parts = key == null ? new String[0] : key.split(";");
        if (parts.length != 4) {
            warn(logger, "Skipping malformed block key '" + key + "' in blocks.yml.");
            return null;
        }
        if (entry == null) {
            warn(logger, "Skipping block '" + key + "' with no settings in blocks.yml.");
            return null;
        }
        PrankType type = PrankType.parse(entry.getString("type"));
        if (type == null) {
            warn(logger, "Skipping block '" + key + "' with unknown type in blocks.yml.");
            return null;
        }
        try {
            String world = parts[0].trim();
            if (world.isEmpty()) {
                warn(logger, "Skipping block '" + key + "' with an empty world in blocks.yml.");
                return null;
            }
            int x = Integer.parseInt(parts[1].trim());
            int y = Integer.parseInt(parts[2].trim());
            int z = Integer.parseInt(parts[3].trim());
            Double chance = entry.contains("chance")
                    ? Math.clamp(entry.getDouble("chance"), 0.0, 1.0)
                    : null;
            Integer restoreDelay = entry.contains("restore-delay-ticks")
                    ? Math.max(1, entry.getInt("restore-delay-ticks"))
                    : null;
            Integer cooldown = entry.contains("cooldown-seconds")
                    ? Math.max(0, entry.getInt("cooldown-seconds"))
                    : null;
            Integer duration = entry.contains("duration-seconds")
                    ? Math.max(1, entry.getInt("duration-seconds"))
                    : null;
            Integer amplifier = entry.contains("amplifier")
                    ? Math.clamp(entry.getInt("amplifier"), 0, 255)
                    : null;
            return new PrankBlock(world, x, y, z, type, chance, restoreDelay, cooldown,
                    blankToNull(entry.getString("effect")), duration, amplifier,
                    blankToNull(entry.getString("break-sound")));
        } catch (NumberFormatException exception) {
            warn(logger, "Skipping block '" + key + "' with invalid coordinates in blocks.yml.");
            return null;
        }
    }

    /** True for blocks whose state can be safely replaced with air and back. */
    public static boolean isSimpleSolid(Block block) {
        Material type = block.getType();
        if (type.isAir() || !type.isSolid() || block.isLiquid()) {
            return false;
        }
        return !(block.getState() instanceof TileState);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String coordinateKey(String world, int x, int y, int z) {
        return normalizeWorld(world) + ";" + x + ";" + y + ";" + z;
    }

    private static void warn(Logger logger, String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (PrankBlock block : blocks.values()) {
            String path = "blocks." + block.key();
            yaml.set(path + ".type", block.type().id());
            if (block.chance() != null) {
                yaml.set(path + ".chance", block.chance());
            }
            if (block.restoreDelayTicks() != null) {
                yaml.set(path + ".restore-delay-ticks", block.restoreDelayTicks());
            }
            if (block.cooldownSeconds() != null) {
                yaml.set(path + ".cooldown-seconds", block.cooldownSeconds());
            }
            if (block.effect() != null) {
                yaml.set(path + ".effect", block.effect());
            }
            if (block.durationSeconds() != null) {
                yaml.set(path + ".duration-seconds", block.durationSeconds());
            }
            if (block.amplifier() != null) {
                yaml.set(path + ".amplifier", block.amplifier());
            }
            if (block.breakSound() != null) {
                yaml.set(path + ".break-sound", block.breakSound());
            }
        }
        synchronized (writeLock) {
            try {
                Path target = file.toPath();
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Path temp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not save blocks.yml: " + exception.getMessage());
            }
        }
    }
}
