package gg.guab.core;

import org.bukkit.plugin.java.JavaPlugin;

public final class GuabCorePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("GuabCore enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("GuabCore disabled.");
    }
}
