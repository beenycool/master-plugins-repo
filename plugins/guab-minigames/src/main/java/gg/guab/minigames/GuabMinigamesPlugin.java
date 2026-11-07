package gg.guab.minigames;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class GuabMinigamesPlugin extends JavaPlugin {

    private BattlePit pit;
    private KillPlane killPlane;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pit = new BattlePit(this);
        this.pit.start();
        this.killPlane = new KillPlane(this);
        this.killPlane.start();
        getServer().getPluginManager().registerEvents(new ArenaDecay(this, pit), this);

        PluginCommand killPlaneCommand = getCommand("killplane");
        if (killPlaneCommand != null) {
            KillPlaneCommand executor = new KillPlaneCommand(this, killPlane);
            killPlaneCommand.setExecutor(executor);
            killPlaneCommand.setTabCompleter(executor);
        }

        PluginCommand command = getCommand("battlepit");
        if (command != null) {
            BattlePitCommand executor = new BattlePitCommand(this, pit);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        PluginCommand parkour = getCommand("parkour");
        if (parkour != null) {
            parkour.setExecutor(new ParkourCommand(this));
        }
        getServer().getPluginManager().registerEvents(new RespawnBellListener(this), this);

        PluginCommand kit = getCommand("kit");
        if (kit != null) {
            kit.setExecutor(new KitCommand(this, pit));
        }

        getLogger().info("GuabMinigames ready (battle pit spawn "
                + pit.formatLocation(pit.spawn()) + ").");
    }

    @Override
    public void onDisable() {
        if (killPlane != null) {
            killPlane.stop();
            killPlane = null;
        }
        if (pit != null) {
            pit.stop();
            pit = null;
        }
        getLogger().info("GuabMinigames disabled.");
    }
}
