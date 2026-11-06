package gg.guab.core;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/** Counts deaths and player kills for the shared stats tracker. */
public final class StatsListener implements Listener {

    private final StatsService stats;

    public StatsListener(StatsService stats) {
        this.stats = stats;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        stats.increment(victim, StatsService.DEATHS, 1);

        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            stats.increment(killer, StatsService.KILLS, 1);
        }
    }
}
