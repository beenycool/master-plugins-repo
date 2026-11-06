package gg.guab.blocks;

import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BlockStore {

    private final GuabBlocksPlugin plugin;
    private final File file;
    private final Map<String, PrankBlock> blocks = new LinkedHashMap<>();

    public BlockStore(GuabBlocksPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "blocks.yml");
        load();
    }

    public static String key(Block block) {
        return block.getWorld().getName() + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ();
    }

    public PrankBlock get(Block block) {
        return blocks.get(key(block));
    }

    public boolean contains(Block block) {
        return blocks.containsKey(key(block));
    }

    public void add(Block block, PrankType type) {
        String key = key(block);
        PrankBlock previous = blocks.get(key);
        PrankBlock entry = previous != null
                ? previous.withType(type)
                : new PrankBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                        type, null, null, null, null, null, null, null);
        blocks.put(key, entry);
        save();
    }

    public boolean remove(Block block) {
        if (blocks.remove(key(block)) == null) {
            return false;
        }
        save();
        return true;
    }

    public Collection<PrankBlock> all() {
        return List.copyOf(blocks.values());
    }

    public int size() {
        return blocks.size();
    }

    public void reload() {
        blocks.clear();
        load();
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("blocks");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            String[] parts = key.split(";");
            if (parts.length != 4) {
                plugin.getLogger().warning("Skipping malformed block key '" + key + "' in blocks.yml.");
                continue;
            }
            try {
                String world = parts[0];
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                ConfigurationSection entry = root.getConfigurationSection(key);
                if (entry == null) {
                    plugin.getLogger().warning("Skipping block '" + key + "' with no settings in blocks.yml.");
                    continue;
                }
                PrankType type = PrankType.parse(entry.getString("type"));
                if (type == null) {
                    plugin.getLogger().warning("Skipping block '" + key + "' with unknown type in blocks.yml.");
                    continue;
                }
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
                blocks.put(key, new PrankBlock(world, x, y, z, type, chance, restoreDelay, cooldown,
                        entry.getString("effect"), duration, amplifier, entry.getString("break-sound")));
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning("Skipping block '" + key + "' with invalid coordinates in blocks.yml.");
            }
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
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save blocks.yml: " + exception.getMessage());
        }
    }
}
