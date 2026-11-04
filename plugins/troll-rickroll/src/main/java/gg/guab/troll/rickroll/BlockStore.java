package gg.guab.troll.rickroll;

import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class BlockStore {

    private final TrollRickrollPlugin plugin;
    private final File file;
    private final Set<String> keys = new HashSet<>();

    public BlockStore(TrollRickrollPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "blocks.yml");
        load();
    }

    public static String key(Block block) {
        return block.getWorld().getName() + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ();
    }

    public boolean contains(Block block) {
        return keys.contains(key(block));
    }

    public void add(Block block) {
        if (keys.add(key(block))) {
            save();
        }
    }

    public void remove(Block block) {
        if (keys.remove(key(block))) {
            save();
        }
    }

    public int size() {
        return keys.size();
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        keys.addAll(YamlConfiguration.loadConfiguration(file).getStringList("blocks"));
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("blocks", new ArrayList<>(keys));
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save blocks.yml: " + exception.getMessage());
        }
    }
}
