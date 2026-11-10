package gg.guab.rickroll;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PackManager implements Listener {

    private final GuabRickrollPlugin plugin;
    private final PackTracker tracker = new PackTracker();
    private UUID packId;
    private boolean warnedOnSend;

    public PackManager(GuabRickrollPlugin plugin) {
        this.plugin = plugin;
        computePackId();
    }

    public void reload() {
        tracker.clear();
        computePackId();
    }

    private void computePackId() {
        RickrollConfig config = plugin.config();
        if (config == null || config.packUrl() == null || config.packUrl().isBlank()) {
            packId = null;
            return;
        }
        // A content-derived UUID keeps the pack identity stable across restarts while still changing when the
        // pack URL or hash changes. Paper echoes this UUID back on PlayerResourcePackStatusEvent.
        String identity = "guab-rickroll|" + config.packUrl().trim() + "|" + config.sha1();
        packId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    public boolean available() {
        RickrollConfig config = plugin.config();
        return config != null && config.packUsable();
    }

    public UUID packId() {
        return packId;
    }

    public boolean maybeSend(Player player) {
        if (!available() || !plugin.config().preload()) {
            return false;
        }
        if (tracker.isReady(player.getUniqueId()) || tracker.isDenied(player.getUniqueId())) {
            return false;
        }
        return send(player);
    }

    /** Sends the rickroll pack. Returns false when the pack is disabled/blank or the API call failed. */
    public boolean send(Player player) {
        RickrollConfig config = plugin.config();
        if (!available() || packId == null) {
            return false;
        }
        try {
            player.setResourcePack(
                    packId,
                    config.packUrl().trim(),
                    config.packHash(),
                    Component.text("Guab wants to show you something."),
                    false);
            tracker.recordRequest(player.getUniqueId(), packId, System.currentTimeMillis());
            warnedOnSend = false;
            return true;
        } catch (Throwable throwable) {
            if (!warnedOnSend) {
                warnedOnSend = true;
                plugin.getLogger().warning("Could not send the rickroll resource pack to " + player.getName()
                        + ": " + throwable.getClass().getSimpleName()
                        + (throwable.getMessage() == null ? "" : " (" + throwable.getMessage() + ")")
                        + "; shows will use the fallback screen.");
            }
            return false;
        }
    }

    /** Clears the remembered state and sends the pack again. Used by {@code /rickroll pack}. */
    public boolean resend(Player player) {
        tracker.forget(player.getUniqueId());
        return send(player);
    }

    public boolean isReady(Player player) {
        return tracker.isReady(player.getUniqueId());
    }

    public boolean isDenied(Player player) {
        return tracker.isDenied(player.getUniqueId());
    }

    public PackTracker.State state(Player player) {
        return tracker.state(player.getUniqueId());
    }

    public void forget(Player player) {
        tracker.forget(player.getUniqueId());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        PackTracker.State state = switch (event.getStatus()) {
            case ACCEPTED -> PackTracker.State.ACCEPTED;
            case DOWNLOADED -> PackTracker.State.DOWNLOADED;
            case SUCCESSFULLY_LOADED -> PackTracker.State.READY;
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> PackTracker.State.DENIED;
        };
        // Ignore statuses for guab-items.zip and any other pack the server may be serving.
        if (!tracker.recordStatus(id, event.getID(), state, System.currentTimeMillis())) {
            return;
        }
        if (state == PackTracker.State.DOWNLOADED || state == PackTracker.State.READY) {
            // DOWNLOADED means the client has the bytes and applies them immediately for server packs.
            // Do not wait for SUCCESSFULLY_LOADED, which can arrive a tick later (or after a client reload).
            plugin.shows().onPackReady(player);
        } else if (state == PackTracker.State.DENIED) {
            plugin.shows().onPackDenied(player);
        }
    }

    public String statusText(Player player) {
        PackTracker.State state = state(player);
        return state.name().toLowerCase(java.util.Locale.ROOT);
    }
}
