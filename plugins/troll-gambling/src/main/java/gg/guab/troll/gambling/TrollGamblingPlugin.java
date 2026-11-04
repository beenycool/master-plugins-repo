package gg.guab.troll.gambling;

import org.bukkit.plugin.java.JavaPlugin;

public final class TrollGamblingPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("TrollGambling enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("TrollGambling disabled.");
    }
}
