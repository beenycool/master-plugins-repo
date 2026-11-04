package gg.guab.troll.core;

import org.bukkit.plugin.java.JavaPlugin;

public final class TrollCorePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("TrollCore enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("TrollCore disabled.");
    }
}
