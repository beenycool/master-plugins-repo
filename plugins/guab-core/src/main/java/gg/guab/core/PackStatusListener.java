package gg.guab.core;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.util.UUID;

/**
 * Feeds server resource-pack handshake events into {@link PackStatusService}.
 *
 * <p>The server pack UUID is the only reliable way to tell {@code guab-items.zip}
 * apart from the additive rickroll pack. When that UUID is known, everything
 * else is ignored. When it is not known (no server pack configured), every
 * status is tracked conservatively; in that setup the rickroll pack embeds the
 * same HUD font, so treating its outcome as the font outcome is still correct.</p>
 */
public final class PackStatusListener implements Listener {

    private final PackStatusService service;

    public PackStatusListener(PackStatusService service) {
        this.service = service;
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (service == null || event == null) {
            return;
        }
        UUID serverPack = service.serverPackId();
        if (serverPack != null && !serverPack.equals(event.getID())) {
            // Status for the rickroll pack or any other additive pack: not ours.
            return;
        }
        service.recordStatus(event.getPlayer().getUniqueId(), map(event.getStatus()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (service == null || event == null) {
            return;
        }
        service.forget(event.getPlayer().getUniqueId());
    }

    static PackStatusService.State map(PlayerResourcePackStatusEvent.Status status) {
        if (status == null) {
            return PackStatusService.State.UNKNOWN;
        }
        return switch (status) {
            case ACCEPTED -> PackStatusService.State.ACCEPTED;
            // DOWNLOADED means the bytes are on the client and the font is usable;
            // do not wait for SUCCESSFULLY_LOADED, which can arrive a tick later.
            case DOWNLOADED, SUCCESSFULLY_LOADED -> PackStatusService.State.READY;
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED ->
                    PackStatusService.State.DECLINED;
        };
    }
}
