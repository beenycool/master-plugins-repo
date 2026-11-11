package gg.guab.core;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * First-visit title and arrival sound for the venue worlds, so players get a
 * visual anchor when they step out of the hub.
 */
final class WorldArrivalListener implements Listener {

    private final Map<UUID, Set<String>> seen = new HashMap<>();
    private final PlayerPreferencesStore preferences;

    WorldArrivalListener(PlayerPreferencesStore preferences) {
        this.preferences = preferences;
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        String world = player.getWorld().getName();
        Set<String> visited = seen.computeIfAbsent(player.getUniqueId(), id -> new HashSet<>());
        if (!visited.add(world)) {
            return;
        }
        boolean titles = preferences.titles(player.getUniqueId());
        boolean sounds = preferences.sounds(player.getUniqueId());
        if (!titles && !sounds) {
            return;
        }
        switch (world) {
            case "guab_casino" -> GuabFx.arrival(player, "CASINO", NamedTextColor.GOLD,
                    Sound.BLOCK_ENDER_CHEST_OPEN, 0.7f, titles, sounds);
            case "guab_darts" -> GuabFx.arrival(player, "DARTS", NamedTextColor.AQUA,
                    Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f, titles, sounds);
            case "guab_cinema" -> GuabFx.arrival(player, "CINEMA", NamedTextColor.LIGHT_PURPLE,
                    Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, titles, sounds);
            default -> {
                // Other worlds are ordinary world/pit travel; no title.
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        seen.remove(event.getPlayer().getUniqueId());
    }
}
