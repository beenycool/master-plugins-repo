package gg.guab.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared action-bar channel with priorities and durations. Higher priorities beat
 * lower ones, and nothing is cleared after a message has already been replaced.
 *
 * <p>Other plugins can fetch this through Bukkit's services manager:
 * {@code getServer().getServicesManager().load(HudChannel.class)}.</p>
 */
public final class HudChannel implements Listener {

    /** Ambient background text, safely overwritten by almost anything. */
    public static final int PRIORITY_AMBIENT = 0;
    /** Navigation/darts hints. */
    public static final int PRIORITY_HINT = 10;
    /** Drunk meter and other passive meters. */
    public static final int PRIORITY_METER = 20;
    /** Points gains/losses. */
    public static final int PRIORITY_POINTS = 30;
    /** Blackjack and other countdown timers. */
    public static final int PRIORITY_TIMER = 40;
    /** Streaks, eliminations and other must-see messages. */
    public static final int PRIORITY_CRITICAL = 50;

    private static final int DEFAULT_DURATION_TICKS = 40;

    private final JavaPlugin plugin;
    private final PlayerPreferencesStore preferences;
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();

    public HudChannel(JavaPlugin plugin, PlayerPreferencesStore preferences) {
        this.plugin = plugin;
        this.preferences = preferences;
    }

    /**
     * Shows an action-bar message when it outranks the one currently on screen.
     *
     * @param priority     higher wins, see the {@code PRIORITY_*} constants
     * @param durationTicks how long the message owns the bar
     */
    public void send(Player player, Component message, int priority, int durationTicks) {
        if (player == null || message == null || !player.isOnline()) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> send(player, message, priority, durationTicks));
            return;
        }
        if (preferences != null && !preferences.actionbar(player.getUniqueId())) {
            return;
        }
        int duration = Math.max(1, durationTicks <= 0 ? DEFAULT_DURATION_TICKS : durationTicks);
        UUID id = player.getUniqueId();
        Active previous = active.get(id);
        if (previous != null && previous.priority > priority) {
            return;
        }
        Active current = new Active(priority);
        active.put(id, current);
        player.sendActionBar(message);
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> clearIfCurrent(player, current), duration);
        current.task = task;
    }

    /** Convenience overload used by points feedback and other short messages. */
    public void send(Player player, Component message, int priority) {
        send(player, message, priority, DEFAULT_DURATION_TICKS);
    }

    /** Removes all tracking and cancels pending clear tasks. */
    public void stop() {
        for (Active entry : active.values()) {
            if (entry.task != null) {
                entry.task.cancel();
            }
        }
        active.clear();
    }

    private void clearIfCurrent(Player player, Active expected) {
        Active current = active.get(player.getUniqueId());
        if (current != expected) {
            return;
        }
        active.remove(player.getUniqueId());
        // Do not send an empty component on expiry: the client fades the old
        // message out by itself, and blanking the bar here made the ambient
        // provider's next repaint flicker. Bookkeeping is still cleared, so a
        // newer message can take over immediately.
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Active entry = active.remove(event.getPlayer().getUniqueId());
        if (entry != null && entry.task != null) {
            entry.task.cancel();
        }
    }

    private static final class Active {
        private final int priority;
        private BukkitTask task;

        private Active(int priority) {
            this.priority = priority;
        }
    }
}
