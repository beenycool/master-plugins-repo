package gg.guab.marriage;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Spouse damage rules plus the small join/quit/move lifecycle hooks. */
public final class MarriageListener implements Listener {

    private final GuabMarriagePlugin plugin;
    private final MarriageService service;

    public MarriageListener(GuabMarriagePlugin plugin, MarriageService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpouseDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player damager;
        if (event.getDamager() instanceof Player direct) {
            damager = direct;
        } else if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            // Bows and thrown projectiles count as spouse damage too.
            damager = shooter;
        } else {
            return;
        }
        if (victim.getUniqueId().equals(damager.getUniqueId())) {
            return;
        }
        MarriageRecord record = plugin.store().find(victim.getUniqueId());
        if (record == null || !record.involves(damager.getUniqueId())) {
            return;
        }
        if (!record.pvp()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        service.handleMove(event.getPlayer(), to);
    }
}
