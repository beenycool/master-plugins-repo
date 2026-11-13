package gg.guab.rickroll;

import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class BlockStore {

    private final GuabRickrollPlugin plugin;
    private final File file;
    private final Set<String> keys = new HashSet<>();

    public BlockStore(GuabRickrollPlugin plugin) {
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

    public boolean remove(Block block) {
        boolean removed = keys.remove(key(block));
        if (removed) {
            save();
        }
        return removed;
    }

    public int size() {
        return keys.size();
    }

    /** Re-reads blocks.yml so {@code /rickroll reload} picks up hand edits. */
    public void reload() {
        keys.clear();
        load();
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
            gg.guab.core.YamlFiles.writeAtomic(file.toPath(), yaml);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save blocks.yml: " + exception.getMessage());
        }
    }
}
