package gg.guab.minigames;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class GuabMinigamesPlugin extends JavaPlugin {

    private BattlePit pit;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pit = new BattlePit(this);
        this.pit.start();

        PluginCommand command = getCommand("battlepit");
        if (command != null) {
            BattlePitCommand executor = new BattlePitCommand(this, pit);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("GuabMinigames ready (battle pit spawn "
                + pit.formatLocation(pit.spawn()) + ").");
    }

    @Override
    public void onDisable() {
        if (pit != null) {
            pit.stop();
            pit = null;
        }
        getLogger().info("GuabMinigames disabled.");
    }
}
