package gg.guab.marriage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory proposal bookkeeping. Every operation takes the current time from
 * the caller so the timeout boundary can be tested without sleeping.
 *
 * <p>Proposals are deliberately not persisted: a restart should clear every
 * unanswered request rather than surprise a player with an old one.</p>
 */
public final class Proposals {

    public record Proposal(
            UUID proposer,
            String proposerName,
            UUID target,
            String targetName,
            long createdAt) {

        public boolean expired(long now, long timeoutMillis) {
            return now - createdAt >= timeoutMillis;
        }
    }

    /** target uuid -> proposer uuid -> proposal */
    private final Map<UUID, Map<UUID, Proposal>> pending = new LinkedHashMap<>();
    private long timeoutMillis;

    public Proposals(long timeoutMillis) {
        setTimeoutMillis(timeoutMillis);
    }

    public synchronized void setTimeoutMillis(long millis) {
        this.timeoutMillis = Math.max(1L, millis);
    }

    public synchronized long timeoutMillis() {
        return timeoutMillis;
    }

    /** Creates a proposal unless it is a self-proposal or a live duplicate exists. */
    public synchronized boolean propose(UUID proposer, String proposerName, UUID target,
                                        String targetName, long now) {
        if (proposer == null || target == null || proposer.equals(target)) {
            return false;
        }
        Map<UUID, Proposal> incoming = pending.computeIfAbsent(target, ignored -> new LinkedHashMap<>());
        Proposal existing = incoming.get(proposer);
        if (existing != null && !existing.expired(now, timeoutMillis)) {
            return false;
        }
        incoming.put(proposer, new Proposal(proposer, clean(proposerName), target,
                clean(targetName), now));
        return true;
    }

    /** Live proposal from {@code proposer} to {@code target}, or null. */
    public synchronized Proposal find(UUID target, UUID proposer, long now) {
        if (target == null || proposer == null) {
            return null;
        }
        Map<UUID, Proposal> incoming = pending.get(target);
        if (incoming == null) {
            return null;
        }
        Proposal proposal = incoming.get(proposer);
        if (proposal == null) {
            return null;
        }
        if (proposal.expired(now, timeoutMillis)) {
            incoming.remove(proposer);
            if (incoming.isEmpty()) {
                pending.remove(target);
            }
            return null;
        }
        return proposal;
    }

    /** All live proposals waiting for {@code target}, oldest first. */
    public synchronized List<Proposal> pendingFor(UUID target, long now) {
        Map<UUID, Proposal> incoming = pending.get(target);
        if (incoming == null) {
            return List.of();
        }
        prune(target, incoming, now);
        if (incoming.isEmpty()) {
            return List.of();
        }
        return List.copyOf(incoming.values());
    }

    /** Case-insensitive proposer lookup for {@code /marry accept <name>}. */
    public synchronized Proposal findByName(UUID target, String proposerName, long now) {
        if (proposerName == null || proposerName.isBlank()) {
            return null;
        }
        String wanted = proposerName.trim();
        for (Proposal proposal : pendingFor(target, now)) {
            if (proposal.proposerName().equalsIgnoreCase(wanted)) {
                return proposal;
            }
        }
        return null;
    }

    public synchronized Proposal remove(UUID target, UUID proposer) {
        Map<UUID, Proposal> incoming = pending.get(target);
        if (incoming == null) {
            return null;
        }
        Proposal removed = incoming.remove(proposer);
        if (incoming.isEmpty()) {
            pending.remove(target);
        }
        return removed;
    }

    public synchronized boolean remove(Proposal proposal) {
        return proposal != null && remove(proposal.target(), proposal.proposer()) != null;
    }

    /** Drops everything waiting for or sent by a player (used on quit and marriage). */
    public synchronized void clear(UUID player) {
        if (player == null) {
            return;
        }
        pending.remove(player);
        pending.values().removeIf(incoming -> {
            incoming.remove(player);
            return incoming.isEmpty();
        });
    }

    public synchronized void clearAll() {
        pending.clear();
    }

    /** Removes expired proposals everywhere and returns how many were live. */
    public synchronized int prune(long now) {
        int live = 0;
        for (Map.Entry<UUID, Map<UUID, Proposal>> entry : new ArrayList<>(pending.entrySet())) {
            Map<UUID, Proposal> incoming = entry.getValue();
            prune(entry.getKey(), incoming, now);
            live += incoming.size();
        }
        return live;
    }

    public synchronized int size(long now) {
        return prune(now);
    }

    private void prune(UUID target, Map<UUID, Proposal> incoming, long now) {
        incoming.values().removeIf(proposal -> proposal.expired(now, timeoutMillis));
        if (incoming.isEmpty()) {
            pending.remove(target);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
