package gg.guab.minigames;

import gg.guab.core.StatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Kills players who drop below a configurable Y level, so falling out of the
 * play area is fatal instead of a long ride into the void.
 */
public final class KillPlane implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final GuabMinigamesPlugin plugin;
    private final Set<UUID> killedByPlane = new HashSet<>();
    private BukkitTask task;

    public KillPlane(GuabMinigamesPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        killedByPlane.clear();
    }

    private void tick() {
        if (!enabled()) {
            return;
        }
        double limit = threshold();
        List<String> worlds = worlds();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead()) {
                continue;
            }
            if (!worlds.isEmpty() && !worlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (ignored(player.getGameMode())) {
                continue;
            }
            if (player.getLocation().getY() >= limit) {
                continue;
            }
            kill(player);
        }
    }

    private void kill(Player player) {
        UUID id = player.getUniqueId();
        killedByPlane.add(id);
        player.sendMessage(prefix().append(LEGACY.deserialize(message())));
        player.setHealth(0.0);
        if (!player.isDead()) {
            killedByPlane.remove(id);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(PlayerDeathEvent event) {
        if (!killedByPlane.remove(event.getEntity().getUniqueId())) {
            return;
        }
        StatsService stats = stats();
        if (stats != null) {
            stats.increment(event.getEntity(), StatsService.VOID_DEATHS, 1);
        }
        event.deathMessage(Component.text(event.getEntity().getName() + " fell out of the world",
                NamedTextColor.RED));
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("killplane.enabled", true);
    }

    public void setEnabled(boolean enabled) {
        plugin.getConfig().set("killplane.enabled", enabled);
        plugin.saveConfig();
    }

    public double threshold() {
        return plugin.getConfig().getDouble("killplane.y", -66.0);
    }

    public void setThreshold(double y) {
        plugin.getConfig().set("killplane.y", y);
        plugin.saveConfig();
    }

    public List<String> worlds() {
        return plugin.getConfig().getStringList("killplane.worlds").stream()
                .map(world -> world.toLowerCase(Locale.ROOT))
                .toList();
    }

    public String message() {
        return plugin.getConfig().getString("killplane.message", "&cYou fell out of the world.");
    }

    private boolean ignored(GameMode mode) {
        for (String raw : plugin.getConfig().getStringList("killplane.ignore-gamemodes")) {
            if (raw.equalsIgnoreCase(mode.name())) {
                return true;
            }
        }
        return false;
    }

    private StatsService stats() {
        var registration = Bukkit.getServicesManager().getRegistration(StatsService.class);
        return registration == null ? null : registration.getProvider();
    }

    private Component prefix() {
        return Component.text("[KillPlane] ", NamedTextColor.DARK_RED);
    }
}
