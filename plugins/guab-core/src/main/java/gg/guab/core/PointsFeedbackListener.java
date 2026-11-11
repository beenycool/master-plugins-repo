package gg.guab.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Turns silent ledger writes into a tiny sound and an action-bar number. Changes
 * are netted over a short window, so a bet followed by a payout reports the real
 * result instead of a misleading "-50" followed by silence.
 */
final class PointsFeedbackListener implements Listener {

    private static final long WINDOW_TICKS = 5L;

    private final GuabCorePlugin plugin;
    private final PlayerPreferencesStore preferences;
    private final HudChannel channel;
    private final Map<UUID, Pending> pending = new HashMap<>();

    PointsFeedbackListener(GuabCorePlugin plugin, PlayerPreferencesStore preferences,
                           HudChannel channel) {
        this.plugin = plugin;
        this.preferences = preferences;
        this.channel = channel;
    }

    @EventHandler
    public void onChange(GuabPointsChangeEvent event) {
        if (Bukkit.isPrimaryThread()) {
            accumulate(event.player(), event.delta());
        } else {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> accumulate(event.player(), event.delta()));
        }
    }

    private void accumulate(UUID player, long delta) {
        Pending entry = pending.computeIfAbsent(player, id -> new Pending());
        entry.delta += delta;
        if (entry.scheduled) {
            return;
        }
        entry.scheduled = true;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> flush(player), WINDOW_TICKS);
    }

    private void flush(UUID id) {
        Pending entry = pending.remove(id);
        if (entry == null || entry.delta == 0) {
            return;
        }
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) {
            return;
        }
        boolean gain = entry.delta > 0;
        if (preferences.sounds(id)) {
            player.playSound(player.getLocation(),
                    gain ? Sound.ENTITY_EXPERIENCE_ORB_PICKUP : Sound.BLOCK_NOTE_BLOCK_BASS,
                    SoundCategory.PLAYERS, 0.6f, gain ? 1.4f : 0.8f);
        }
        channel.send(player, Component.text(
                (gain ? "+" : "") + String.format(Locale.ROOT, "%,d", entry.delta) + " points",
                gain ? NamedTextColor.GREEN : NamedTextColor.RED),
                HudChannel.PRIORITY_POINTS, 40);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    private static final class Pending {
        private long delta;
        private boolean scheduled;
    }
}
