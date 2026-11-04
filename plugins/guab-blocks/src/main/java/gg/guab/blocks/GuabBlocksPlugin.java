package gg.guab.blocks;

import org.bukkit.plugin.java.JavaPlugin;

public final class GuabBlocksPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("GuabBlocks enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("GuabBlocks disabled.");
    }
}
