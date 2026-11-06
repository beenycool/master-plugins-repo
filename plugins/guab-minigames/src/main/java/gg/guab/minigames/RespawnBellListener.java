package gg.guab.minigames;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Plays a huge bell ring every time a player respawns. */
public final class RespawnBellListener implements Listener {

    private final GuabMinigamesPlugin plugin;

    public RespawnBellListener(GuabMinigamesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!plugin.getConfig().getBoolean("respawn-bell.enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        Location location = event.getRespawnLocation();
        float volume = (float) plugin.getConfig().getDouble("respawn-bell.volume", 4.0);
        float pitch = (float) plugin.getConfig().getDouble("respawn-bell.pitch", 0.5);

        player.getWorld().playSound(location, Sound.BLOCK_BELL_USE,
                SoundCategory.MASTER, volume, pitch);
        player.getWorld().playSound(location, Sound.BLOCK_BELL_RESONATE,
                SoundCategory.MASTER, volume, pitch);

        // A second ring makes it feel much bigger.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BELL_USE,
                        SoundCategory.MASTER, volume, pitch * 0.95f);
            }
        }, 8L);
    }
}
