package gg.guab.rickroll;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class PackManager implements Listener {

    private final GuabRickrollPlugin plugin;
    private final Set<UUID> ready = new HashSet<>();
    private final Set<UUID> denied = new HashSet<>();

    public PackManager(GuabRickrollPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        ready.clear();
        denied.clear();
    }

    public void maybeSend(Player player) {
        if (!plugin.config().preload()) {
            return;
        }
        if (ready.contains(player.getUniqueId()) || denied.contains(player.getUniqueId())) {
            return;
        }
        send(player);
    }

    public void send(Player player) {
        String url = plugin.config().packUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        player.setResourcePack(
                url,
                plugin.config().packHash(),
                Component.text("Guab wants to show you something."),
                false);
    }

    public boolean isReady(Player player) {
        return ready.contains(player.getUniqueId());
    }

    public boolean isDenied(Player player) {
        return denied.contains(player.getUniqueId());
    }

    public void forget(Player player) {
        ready.remove(player.getUniqueId());
        denied.remove(player.getUniqueId());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                ready.add(id);
                denied.remove(id);
                plugin.shows().onPackReady(event.getPlayer());
            }
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> {
                denied.add(id);
                ready.remove(id);
                plugin.shows().onPackDenied(event.getPlayer());
            }
            default -> {
            }
        }
    }
}
