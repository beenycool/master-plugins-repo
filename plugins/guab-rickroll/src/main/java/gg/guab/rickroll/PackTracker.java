package gg.guab.rickroll;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks the resource-pack handshake per player. The server also serves {@code guab-items.zip}, so an event is only
 * accepted when its UUID matches the rickroll pack this manager most recently requested for that player.
 *
 * <p>This class deliberately has no Bukkit dependency so the state machine can be unit tested.</p>
 */
final class PackTracker {

    enum State {
        UNKNOWN,
        REQUESTED,
        ACCEPTED,
        DOWNLOADED,
        READY,
        DENIED
    }

    record Entry(UUID packId, State state, long updatedAt, int requests) {
    }

    private final Map<UUID, Entry> entries = new HashMap<>();

    State state(UUID player) {
        Entry entry = entries.get(player);
        return entry == null ? State.UNKNOWN : entry.state();
    }

    UUID packId(UUID player) {
        Entry entry = entries.get(player);
        return entry == null ? null : entry.packId();
    }

    boolean isReady(UUID player) {
        State state = state(player);
        return state == State.DOWNLOADED || state == State.READY;
    }

    boolean isDenied(UUID player) {
        return state(player) == State.DENIED;
    }

    boolean isRequested(UUID player) {
        State state = state(player);
        return state == State.REQUESTED || state == State.ACCEPTED;
    }

    void recordRequest(UUID player, UUID packId, long now) {
        Entry previous = entries.get(player);
        int requests = previous == null ? 1 : previous.requests() + 1;
        entries.put(player, new Entry(packId, State.REQUESTED, now, requests));
    }

    /**
     * Records a status event. Returns {@code false} when the event belongs to another pack (for example
     * {@code guab-items.zip}) and must be ignored.
     */
    boolean recordStatus(UUID player, UUID eventPackId, State state, long now) {
        Entry entry = entries.get(player);
        if (entry == null || entry.packId() == null || !entry.packId().equals(eventPackId)) {
            return false;
        }
        entries.put(player, new Entry(entry.packId(), state, now, entry.requests()));
        return true;
    }

    void forget(UUID player) {
        entries.remove(player);
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }
}
