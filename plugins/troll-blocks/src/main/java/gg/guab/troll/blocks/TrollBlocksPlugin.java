package gg.guab.troll.blocks;

import org.bukkit.plugin.java.JavaPlugin;

public final class TrollBlocksPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("TrollBlocks enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("TrollBlocks disabled.");
    }
}
