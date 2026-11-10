package gg.guab.rickroll;

import gg.guab.core.StatsService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ShowManager {

    public enum RequestResult {
        STARTED,
        QUEUED,
        ALREADY_ACTIVE
    }

    private final GuabRickrollPlugin plugin;
    private final Map<UUID, Show> active = new HashMap<>();
    private final Deque<UUID> queue = new ArrayDeque<>();
    private boolean drainScheduled;

    public ShowManager(GuabRickrollPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isActive(Player player) {
        return active.containsKey(player.getUniqueId());
    }

    public boolean isQueued(Player player) {
        return queue.contains(player.getUniqueId());
    }

    public boolean isBusy() {
        return !active.isEmpty();
    }

    public int activeCount() {
        return active.size();
    }

    public int queueSize() {
        return queue.size();
    }

    public String activeName() {
        Show show = active.values().stream().findFirst().orElse(null);
        return show == null ? null : show.playerName();
    }

    public int activeElapsedSeconds() {
        Show show = active.values().stream().findFirst().orElse(null);
        return show == null ? 0 : show.elapsedSeconds();
    }

    public List<String> queuedNames() {
        List<String> names = new ArrayList<>();
        for (UUID id : queue) {
            Player player = Bukkit.getPlayer(id);
            names.add(player == null ? id.toString() : player.getName());
        }
        return names;
    }

    /**
     * Explicit {@code /rickroll test} request: queues while the single cinema seat/screen is busy.
     */
    public RequestResult request(Player player) {
        if (isActive(player)) {
            return RequestResult.ALREADY_ACTIVE;
        }
        if (isBusy() || !queue.isEmpty()) {
            if (!queue.contains(player.getUniqueId())) {
                queue.addLast(player.getUniqueId());
            }
            return RequestResult.QUEUED;
        }
        startNow(player);
        return RequestResult.STARTED;
    }

    /**
     * Natural block trigger: never queues, and never stacks a second victim onto the one-seat cinema.
     */
    public boolean tryStartNatural(Player player) {
        if (isActive(player) || isBusy() || !queue.isEmpty()) {
            return false;
        }
        startNow(player);
        return true;
    }

    private void startNow(Player player) {
        Show show = new Show(plugin, player);
        active.put(player.getUniqueId(), show);
        countRickroll(player);
        show.begin();
    }

    private void countRickroll(Player player) {
        var registration = plugin.getServer().getServicesManager().getRegistration(StatsService.class);
        if (registration != null) {
            registration.getProvider().increment(player, StatsService.RICKROLLS, 1);
        }
    }

    public void stop(Player player) {
        Show show = active.get(player.getUniqueId());
        if (show != null) {
            show.finish();
            return;
        }
        queue.remove(player.getUniqueId());
    }

    public void quit(Player player) {
        queue.remove(player.getUniqueId());
        Show show = active.remove(player.getUniqueId());
        if (show != null) {
            show.quit();
        }
    }

    public void stopAll() {
        queue.clear();
        for (Show show : List.copyOf(active.values())) {
            show.finish();
        }
        active.clear();
    }

    public void reload() {
        for (Show show : List.copyOf(active.values())) {
            show.onReload();
        }
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
        scheduleNext();
    }

    private void scheduleNext() {
        if (drainScheduled || queue.isEmpty() || !plugin.isEnabled()) {
            return;
        }
        drainScheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            drainScheduled = false;
            startNext();
        });
    }

    private void startNext() {
        while (!queue.isEmpty()) {
            UUID id = queue.pollFirst();
            if (id == null || active.containsKey(id)) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            startNow(player);
            return;
        }
    }
}
