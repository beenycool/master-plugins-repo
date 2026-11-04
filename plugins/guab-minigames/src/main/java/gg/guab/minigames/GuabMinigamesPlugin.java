package gg.guab.minigames;

import org.bukkit.plugin.java.JavaPlugin;

public final class GuabMinigamesPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("GuabMinigames enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("GuabMinigames disabled.");
    }
}
