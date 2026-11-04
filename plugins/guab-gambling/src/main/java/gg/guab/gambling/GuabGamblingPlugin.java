package gg.guab.gambling;

import org.bukkit.plugin.java.JavaPlugin;

public final class GuabGamblingPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("GuabGambling enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("GuabGambling disabled.");
    }
}
