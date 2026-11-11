package gg.guab.minigames;

import gg.guab.core.HudChannel;
import gg.guab.core.HudContextService;
import gg.guab.core.PlayerPreferencesStore;
import net.kyori.adventure.text.Component;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class GuabMinigamesPlugin extends JavaPlugin {

    private BattlePit pit;
    private KillPlane killPlane;
    private KitCommand kit;

    BattlePit pit() {
        return pit;
    }

    KitCommand kit() {
        return kit;
    }

    /** Whether the shared battle pit loadout feature is enabled. */
    boolean kitEnabled() {
        return getConfig().getBoolean("kit.enabled", true);
    }

    /**
     * Routes action-bar messages through the shared core channel so points,
     * darts and casino timers are not stomped by background minigame text.
     * Falls back to a direct send when GuabCore's channel is unavailable.
     */
    void actionBar(Player player, Component message, int priority, int durationTicks) {
        HudChannel channel = getServer().getServicesManager().load(HudChannel.class);
        if (channel != null) {
            channel.send(player, message, priority, durationTicks);
            return;
        }
        PlayerPreferencesStore preferences = getServer().getServicesManager()
                .load(PlayerPreferencesStore.class);
        if (preferences == null || preferences.actionbar(player.getUniqueId())) {
            player.sendActionBar(message);
        }
    }

    /** True when the player still wants sounds (defaults to yes). */
    boolean sounds(Player player) {
        PlayerPreferencesStore preferences = getServer().getServicesManager()
                .load(PlayerPreferencesStore.class);
        return preferences == null || preferences.sounds(player.getUniqueId());
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pit = new BattlePit(this);
        this.pit.start();
        getServer().getServicesManager().register(HudContextService.class,
                new PitHudContext(this), this, ServicePriority.Normal);
        this.killPlane = new KillPlane(this);
        this.killPlane.start();
        getServer().getPluginManager().registerEvents(new ArenaDecay(this, pit), this);
        getServer().getPluginManager().registerEvents(new KitProtectionListener(this), this);

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
        getServer().getPluginManager().registerEvents(new RespawnBellListener(this), this);

        this.kit = new KitCommand(this, pit);
        getServer().getPluginManager().registerEvents(kit, this);
        PluginCommand kitCommand = getCommand("kit");
        if (kitCommand != null) {
            kitCommand.setExecutor(kit);
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
        kit = null;
        getLogger().info("GuabMinigames disabled.");
    }
}
