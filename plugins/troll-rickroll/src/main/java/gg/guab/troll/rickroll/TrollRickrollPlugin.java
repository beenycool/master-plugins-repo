package gg.guab.troll.rickroll;

import org.bukkit.plugin.java.JavaPlugin;

public final class TrollRickrollPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("TrollRickroll enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("TrollRickroll disabled.");
    }
}
