package gg.guab.rickroll;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ShowManager {

    private final GuabRickrollPlugin plugin;
    private final Map<UUID, Show> active = new HashMap<>();

    public ShowManager(GuabRickrollPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isActive(Player player) {
        return active.containsKey(player.getUniqueId());
    }

    public void start(Player player) {
        if (isActive(player)) {
            return;
        }
        Show show = new Show(plugin, player);
        active.put(player.getUniqueId(), show);
        show.begin();
    }

    public void stop(Player player) {
        Show show = active.get(player.getUniqueId());
        if (show != null) {
            show.finish();
        }
    }

    public void quit(Player player) {
        Show show = active.remove(player.getUniqueId());
        if (show != null) {
            show.quit();
        }
    }

    public void stopAll() {
        for (Show show : List.copyOf(active.values())) {
            show.finish();
        }
        active.clear();
    }

    public void onPackReady(Player player) {
        Show show = active.get(player.getUniqueId());
        if (show != null) {
            show.onPackReady();
        }
    }

    public void onPackDenied(Player player) {
        Show show = active.get(player.getUniqueId());
        if (show != null) {
            show.onPackDenied();
        }
    }

    void remove(Show show) {
        active.remove(show.playerId());
    }
}
