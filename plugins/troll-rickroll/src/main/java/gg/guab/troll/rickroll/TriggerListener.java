package gg.guab.troll.rickroll;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class TriggerListener implements Listener {

    private final TrollRickrollPlugin plugin;
    private final Map<UUID, Long> lastTrigger = new HashMap<>();

    public TriggerListener(TrollRickrollPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (plugin.shows().isActive(player)) {
            event.setTo(event.getFrom());
            return;
        }
        if (!event.hasChangedBlock()) {
            return;
        }

        Block below = event.getTo().getBlock().getRelative(BlockFace.DOWN);
        if (!plugin.blocks().contains(below)) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastTrigger.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < plugin.config().cooldownMillis()) {
            return;
        }
        lastTrigger.put(player.getUniqueId(), now);

        if (ThreadLocalRandom.current().nextDouble() < plugin.config().chance()) {
            plugin.shows().start(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        restoreStale(player);
        plugin.packs().maybeSend(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.shows().quit(event.getPlayer());
        plugin.packs().forget(event.getPlayer());
    }

    private void restoreStale(Player player) {
        NamespacedKey key = new NamespacedKey(plugin, "active");
        String mode = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (mode == null) {
            return;
        }
        try {
            player.setGameMode(GameMode.valueOf(mode));
        } catch (IllegalArgumentException exception) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        player.resetPlayerTime();
        player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
        player.getPersistentDataContainer().remove(key);
        plugin.getLogger().info("Restored " + player.getName() + " after an interrupted rickroll.");
    }
}
