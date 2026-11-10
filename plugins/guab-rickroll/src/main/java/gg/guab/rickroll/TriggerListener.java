package gg.guab.rickroll;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class TriggerListener implements Listener {

    private final GuabRickrollPlugin plugin;
    private final Map<UUID, Long> lastTrigger = new HashMap<>();
    private final Map<UUID, Long> joinedAt = new HashMap<>();
    private final Map<UUID, Long> lastTeleport = new HashMap<>();
    private boolean paused;

    public TriggerListener(GuabRickrollPlugin plugin) {
        this.plugin = plugin;
        this.paused = plugin.config().paused();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (event.isCancelled()) {
            return;
        }

        if (plugin.shows().isActive(player)) {
            Location to = event.getTo();
            if (to == null) {
                return;
            }
            Location locked = event.getFrom().clone();
            locked.setYaw(to.getYaw());
            locked.setPitch(to.getPitch());
            event.setTo(locked);
            return;
        }
        if (!event.hasChangedBlock()) {
            return;
        }
        if (paused || !allowed(player)) {
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

        if (ThreadLocalRandom.current().nextDouble() < plugin.config().chance()) {
            // Stamp only when a show actually starts. A failed chance roll must not consume the cooldown.
            if (plugin.shows().tryStartNatural(player)) {
                lastTrigger.put(player.getUniqueId(), now);
            }
        }
    }

    private boolean allowed(Player player) {
        RickrollConfig config = plugin.config();
        if (plugin.rickrollOptOut(player)) {
            return false;
        }
        String world = player.getWorld().getName();
        for (String ignored : config.ignoreWorlds()) {
            if (ignored != null && ignored.equalsIgnoreCase(world)) {
                return false;
            }
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.SPECTATOR || config.ignoreGameModes().contains(mode)) {
            return false;
        }
        if (config.ignoreVehicles() && player.getVehicle() != null) {
            return false;
        }
        if (config.ignoreGliding() && player.isGliding()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long grace = config.joinGraceSeconds() * 1000L;
        if (grace > 0 && now - joinedAt.getOrDefault(player.getUniqueId(), 0L) < grace) {
            return false;
        }
        long teleportGrace = config.teleportGraceSeconds() * 1000L;
        if (teleportGrace > 0 && now - lastTeleport.getOrDefault(player.getUniqueId(), 0L) < teleportGrace) {
            return false;
        }
        return true;
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        lastTeleport.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        joinedAt.put(player.getUniqueId(), System.currentTimeMillis());
        restoreStale(player);
        plugin.packs().maybeSend(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.shows().quit(player);
        plugin.packs().forget(player);
        UUID id = player.getUniqueId();
        lastTrigger.remove(id);
        joinedAt.remove(id);
        lastTeleport.remove(id);
    }

    /** Reloads the pause switch after {@code /rickroll reload}. */
    public void reloadConfig() {
        this.paused = plugin.config().paused();
    }

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        plugin.getConfig().set("trigger.paused", paused);
        plugin.saveConfig();
    }

    public long cooldownRemainingMillis(Player player) {
        Long last = lastTrigger.get(player.getUniqueId());
        if (last == null) {
            return 0L;
        }
        return Math.max(0L, plugin.config().cooldownMillis() - (System.currentTimeMillis() - last));
    }

    private void restoreStale(Player player) {
        NamespacedKey key = new NamespacedKey(plugin, "active");
        NamespacedKey backKey = new NamespacedKey(plugin, "back");
        String marker = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (marker == null) {
            return;
        }
        try {
            PostEffects.clear(plugin, player);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not clear stale cinematic post effects for " + player.getName()
                    + " (" + throwable.getClass().getSimpleName() + ").");
        }
        try {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            player.stopSound(Sound.MUSIC_DISC_PIGSTEP);
            player.stopSound("guab:rickroll");
        } catch (Throwable ignored) {
            // The player is still connecting; there is nothing else to clear.
        }

        PlayerState state = PlayerState.parse(marker);
        if (state != null) {
            state.apply(player, true, plugin);
        } else {
            restoreLegacy(player, marker, backKey);
        }

        player.getPersistentDataContainer().remove(key);
        player.getPersistentDataContainer().remove(backKey);
        plugin.getLogger().info("Restored " + player.getName() + " after an interrupted rickroll.");
    }

    private void restoreLegacy(Player player, String rawMode, NamespacedKey backKey) {
        try {
            player.setGameMode(GameMode.valueOf(rawMode));
        } catch (IllegalArgumentException exception) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        player.resetPlayerTime();
        player.setFlySpeed(0.05f);
        player.setWalkSpeed(0.1f);
        player.setAllowFlight(player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR);
        var back = Show.deserializeLocation(
                player.getPersistentDataContainer().get(backKey, PersistentDataType.STRING));
        Location destination = back;
        if (destination == null && !Bukkit.getWorlds().isEmpty()) {
            destination = Bukkit.getWorlds().getFirst().getSpawnLocation();
        }
        if (destination != null) {
            player.teleport(destination);
        }
    }
}
