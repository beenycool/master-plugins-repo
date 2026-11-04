package gg.guab.items;

import org.bukkit.plugin.java.JavaPlugin;

public final class GuabItemsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("GuabItems enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("GuabItems disabled.");
    }
}
