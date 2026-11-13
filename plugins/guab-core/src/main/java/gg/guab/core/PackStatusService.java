package gg.guab.core;

import org.bukkit.Bukkit;
import org.bukkit.packs.ResourcePack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers whether each player accepted the server's resource pack
 * ({@code guab-items.zip}, which carries the {@code guab:hud} font).
 *
 * <p>Clients that decline or fail the pack have no custom glyphs, so callers
 * should fall back to plain ASCII instead of showing missing-character boxes.
 * The service is null-safe and can be fetched through Bukkit's services
 * manager: {@code getServer().getServicesManager().load(PackStatusService.class)}.</p>
 */
public final class PackStatusService {

    public enum State {
        UNKNOWN,
        REQUESTED,
        ACCEPTED,
        READY,
        DECLINED
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private volatile UUID serverPackId;
    private volatile boolean resolvedServerPack;

    /** Resolves the server pack UUID lazily so startup order cannot matter. */
    public PackStatusService() {
    }

    /** Fixed server pack UUID; useful for tests or when the caller already knows it. */
    public PackStatusService(UUID serverPackId) {
        this.serverPackId = serverPackId;
        this.resolvedServerPack = true;
    }

    /**
     * The UUID Paper uses for the server resource pack, or {@code null} when the
     * server is not serving one. Resolved once and cached; the value only changes
     * with a server restart.
     */
    public UUID serverPackId() {
        if (!resolvedServerPack) {
            UUID id = null;
            try {
                ResourcePack pack = Bukkit.getServer().getServerResourcePack();
                id = pack == null ? null : pack.getId();
            } catch (RuntimeException ignored) {
                // No server pack configured, or the server is not ready yet.
            }
            serverPackId = id;
            resolvedServerPack = true;
        }
        return serverPackId;
    }

    public State state(UUID player) {
        if (player == null) {
            return State.UNKNOWN;
        }
        return states.getOrDefault(player, State.UNKNOWN);
    }

    /** True when the GuabCore config still allows bitmap HUD icons. */
    public boolean fontIconsEnabled() {
        var core = Bukkit.getPluginManager().getPlugin("GuabCore");
        return core == null || core.getConfig().getBoolean("hud.font-icons", true);
    }

    /** True once the client has accepted and loaded the server pack. */
    public boolean customFonts(UUID player) {
        State state = state(player);
        return state == State.ACCEPTED || state == State.READY;
    }

    /** True when the client declined or failed to load the pack. */
    public boolean declined(UUID player) {
        return state(player) == State.DECLINED;
    }

    public void recordRequest(UUID player) {
        if (player == null) {
            return;
        }
        states.put(player, State.REQUESTED);
    }

    public void recordStatus(UUID player, State state) {
        if (player == null || state == null) {
            return;
        }
        states.put(player, state);
    }

    /** Forgets what this player reported, so the next status event starts fresh. */
    public void refresh(org.bukkit.entity.Player player) {
        if (player == null) {
            return;
        }
        states.remove(player.getUniqueId());
    }

    public void forget(UUID player) {
        if (player != null) {
            states.remove(player);
        }
    }

    /** Drops every cached player state; used on plugin shutdown. */
    public void clear() {
        states.clear();
    }
}
