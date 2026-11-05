package gg.guab.gambling;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;

/**
 * Keeps the casino a venue, not a building site: only admins can change blocks, and
 * players cannot be hurt or starved while they are inside.
 */
public final class CasinoGuard implements Listener {

    private final CasinoVenue venue;

    public CasinoGuard(CasinoVenue venue) {
        this.venue = venue;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (venue.isCasinoWorld(event.getBlock().getWorld())
                && !event.getPlayer().hasPermission("guab.casino.build")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (venue.isCasinoWorld(event.getBlock().getWorld())
                && !event.getPlayer().hasPermission("guab.casino.build")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && venue.isCasinoWorld(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player && venue.isCasinoWorld(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }
}
