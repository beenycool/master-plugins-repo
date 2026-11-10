package gg.guab.core;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class GuabCorePlugin extends JavaPlugin {

    private PointsStore points;
    private StatsStore stats;
    private PlayerPreferencesStore preferences;
    private HudChannel hudChannel;
    private HudManager hud;
    private BukkitTask saveTask;
    private boolean warnedPointsLoadFailure;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.preferences = new PlayerPreferencesStore(
                getDataFolder().toPath().resolve("preferences"));
        getServer().getServicesManager().register(PlayerPreferencesStore.class, preferences,
                this, ServicePriority.Normal);

        this.points = new PointsStore(getDataFolder().toPath().resolve("points.yml"));
        this.points.load();
        if (this.points.loadFailed()) {
            warnedPointsLoadFailure = true;
            getLogger().severe("points.yml could not be read (" + this.points.loadError()
                    + "). The file has been preserved as points.yml.bak; the empty ledger will"
                    + " not overwrite it.");
        }
        getServer().getServicesManager().register(PointsService.class, points, this,
                ServicePriority.Normal);

        this.stats = new StatsStore(getDataFolder().toPath().resolve("stats.yml"));
        this.stats.load();
        getServer().getServicesManager().register(StatsService.class, stats, this,
                ServicePriority.Normal);

        this.hudChannel = new HudChannel(this, preferences);
        getServer().getServicesManager().register(HudChannel.class, hudChannel, this,
                ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(hudChannel, this);

        getServer().getPluginManager().registerEvents(new StatsListener(stats), this);
        getServer().getPluginManager().registerEvents(
                new PointsFeedbackListener(this, preferences, hudChannel), this);
        getServer().getPluginManager().registerEvents(new WorldArrivalListener(preferences), this);
        getServer().getPluginManager().registerEvents(new JoinGuideListener(this), this);

        boolean hudEnabled = getConfig().getBoolean("hud.enabled", true);
        if (hudEnabled) {
            this.hud = new HudManager(this, points, stats, preferences);
            this.hud.start();
        }

        // The welcome guide and settings work even when the HUD is disabled.
        SettingsCommand settingsCommand = new SettingsCommand(preferences, hud);
        bind("hud", settingsCommand);
        bind("settings", settingsCommand);

        PointsCommand pointsCommand = new PointsCommand(this);
        getServer().getPluginManager().registerEvents(pointsCommand, this);
        bind("points", pointsCommand);
        bind("top", pointsCommand);

        StatsCommand statsCommand = new StatsCommand(this);
        bind("stats", statsCommand);

        SpawnCommand spawnCommand = new SpawnCommand(this, preferences);
        bind("spawn", spawnCommand);
        bind("hub", spawnCommand);

        CoreAdminCommand coreAdmin = new CoreAdminCommand(this);
        bind("guabcore", coreAdmin);

        scheduleSaveTask();

        boolean vault = getServer().getPluginManager().getPlugin("Vault") != null
                && VaultHook.install(this, points);

        getLogger().info("GuabCore ready (" + points.size() + " balances"
                + (vault ? ", Vault economy registered" : "") + ").");
    }

    private void bind(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            return;
        }
        command.setExecutor(executor);
        if (executor instanceof org.bukkit.command.TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }

    @Override
    public void onDisable() {
        // Flush the ledgers before tearing down the HUD: a scoreboard failure during
        // shutdown must never skip the points/stats save.
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
        if (stats != null) {
            try {
                stats.save();
            } catch (RuntimeException exception) {
                getLogger().severe("Could not save stats.yml: " + exception.getMessage());
            }
        }
        if (preferences != null) {
            try {
                preferences.saveAll();
            } catch (RuntimeException exception) {
                getLogger().severe("Could not save player preferences: "
                        + exception.getMessage());
            }
        }
        if (hud != null) {
            try {
                hud.stop();
            } catch (RuntimeException exception) {
                getLogger().warning("Could not detach the HUD cleanly: " + exception.getMessage());
            }
            hud = null;
        }
        if (hudChannel != null) {
            hudChannel.stop();
            hudChannel = null;
        }
        getLogger().info("GuabCore disabled.");
    }

    private void scheduleSaveTask() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        int seconds = Math.max(30, getConfig().getInt("save-interval-seconds", 60));
        long ticks = seconds * 20L;
        this.saveTask = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, this::saveQuietly, ticks, ticks);
    }

    /** Flush every core-owned store now; used by /guabcore save and event snapshots. */
    public void saveNow() {
        points.save();
        stats.save();
        preferences.saveAll();
    }

    /** Reload config values that are read dynamically, plus the save timer. */
    public void reloadCore() {
        reloadConfig();
        scheduleSaveTask();
    }

    private void saveQuietly() {
        try {
            points.save();
        } catch (RuntimeException exception) {
            if (!warnedPointsLoadFailure || points.size() > 0) {
                getLogger().severe("Could not save points.yml: " + exception.getMessage());
            }
        }
        try {
            stats.save();
        } catch (RuntimeException exception) {
            getLogger().severe("Could not save stats.yml: " + exception.getMessage());
        }
        // Preferences write themselves immediately on toggle; no periodic flush needed.
    }

    public PointsService points() {
        return points;
    }

    public PointsStore pointsStore() {
        return points;
    }

    public StatsService stats() {
        return stats;
    }

    public StatsStore statsStore() {
        return stats;
    }

    public PlayerPreferencesStore preferences() {
        return preferences;
    }

    public HudChannel hudChannel() {
        return hudChannel;
    }

    public boolean hudEnabled() {
        return hud != null;
    }
}
