package gg.guab.troll.minigames;

import org.bukkit.plugin.java.JavaPlugin;

public final class TrollMinigamesPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("TrollMinigames enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("TrollMinigames disabled.");
    }
}
