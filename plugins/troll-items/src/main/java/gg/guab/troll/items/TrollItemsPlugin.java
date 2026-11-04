package gg.guab.troll.items;

import org.bukkit.plugin.java.JavaPlugin;

public final class TrollItemsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("TrollItems enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("TrollItems disabled.");
    }
}
