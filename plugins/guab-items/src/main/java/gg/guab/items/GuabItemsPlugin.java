package gg.guab.items;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class GuabItemsPlugin extends JavaPlugin {

    private ItemsConfig config;
    private ItemFactory items;
    private Cooldowns cooldowns;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = ItemsConfig.load(this);
        this.items = new ItemFactory(this);
        this.cooldowns = new Cooldowns();

        getServer().getPluginManager().registerEvents(new ItemListener(this), this);
        getServer().getPluginManager().registerEvents(new BeerTapListener(this), this);

        PluginCommand command = getCommand("guabitems");
        if (command != null) {
            GuabItemsCommand executor = new GuabItemsCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("GuabItems ready (wind charge power=" + config.windChargePower()
                + ", jump boost amplifier=" + config.jumpBoostAmplifier() + ").");
    }

    public void reload() {
        reloadConfig();
        this.config = ItemsConfig.load(this);
    }

    public ItemsConfig config() {
        return config;
    }

    public ItemFactory items() {
        return items;
    }

    public Cooldowns cooldowns() {
        return cooldowns;
    }

    @Override
    public void onDisable() {
        getLogger().info("GuabItems disabled.");
    }
}
