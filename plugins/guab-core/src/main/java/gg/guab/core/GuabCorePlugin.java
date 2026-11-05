package gg.guab.core;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class GuabCorePlugin extends JavaPlugin {

    private PointsStore points;
    private HudManager hud;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.points = new PointsStore(getDataFolder().toPath().resolve("points.yml"));
        this.points.load();
        getServer().getServicesManager().register(PointsService.class, points, this, ServicePriority.Normal);

        PluginCommand command = getCommand("points");
        if (command != null) {
            PointsCommand executor = new PointsCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        int seconds = Math.max(30, getConfig().getInt("save-interval-seconds", 300));
        long ticks = seconds * 20L;
        this.saveTask = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, this::saveQuietly, ticks, ticks);

        boolean vault = getServer().getPluginManager().getPlugin("Vault") != null
                && VaultHook.install(this, points);

        if (getConfig().getBoolean("hud.enabled", true)) {
            this.hud = new HudManager(this, points);
            this.hud.start();
        }

        getLogger().info("GuabCore ready (" + points.size() + " balances"
                + (vault ? ", Vault economy registered" : "") + ").");
    }

    @Override
    public void onDisable() {
        if (hud != null) {
            hud.stop();
            hud = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (points != null) {
            try {
                points.save();
            } catch (RuntimeException exception) {
                getLogger().severe("Could not save points.yml: " + exception.getMessage());
            }
        }
        getLogger().info("GuabCore disabled.");
    }

    private void saveQuietly() {
        try {
            points.save();
        } catch (RuntimeException exception) {
            getLogger().severe("Could not save points.yml: " + exception.getMessage());
        }
    }

    public PointsService points() {
        return points;
    }
}
