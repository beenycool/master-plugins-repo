package gg.guab.core;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves command arguments without creating phantom offline accounts.
 *
 * <p>Only online players and UUIDs already known to the points ledger or the stats
 * tracker are accepted. Unknown names produce {@code null}, so a typo can never
 * allocate a brand-new zero-balance account.</p>
 */
public final class PlayerResolver {

    public record Match(UUID id, String name, boolean online) {
        public String displayName() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            return id.toString().substring(0, 8);
        }
    }

    private final PointsService points;
    private final StatsService stats;

    public PlayerResolver(PointsService points, StatsService stats) {
        this.points = points;
        this.stats = stats;
    }

    /** Online only, then known offline ledger/stats names. */
    public Match resolve(String input) {
        return pick(input, candidates());
    }

    /** Online players only. Useful for actions that need a live recipient. */
    public Match resolveOnline(String input) {
        List<Match> online = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(new Match(player.getUniqueId(), player.getName(), true));
        }
        return pick(input, online);
    }

    /** Known offline players only. */
    public Match resolveKnownOffline(String input) {
        List<Match> offline = new ArrayList<>();
        for (Match match : candidates()) {
            if (!match.online()) {
                offline.add(match);
            }
        }
        return pick(input, offline);
    }

    private Collection<Match> candidates() {
        Map<UUID, Match> matches = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            matches.put(player.getUniqueId(),
                    new Match(player.getUniqueId(), player.getName(), true));
        }
        if (points != null) {
            for (UUID id : points.knownPlayers()) {
                matches.putIfAbsent(id, offlineMatch(id, null));
            }
        }
        if (stats != null) {
            for (Map.Entry<UUID, String> entry : stats.knownPlayers().entrySet()) {
                matches.putIfAbsent(entry.getKey(), offlineMatch(entry.getKey(), entry.getValue()));
            }
        }
        return matches.values();
    }

    private static Match offlineMatch(UUID id, String knownName) {
        String name = knownName;
        if (name == null || name.isBlank()) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
            name = offline == null ? null : offline.getName();
        }
        return new Match(id, name, false);
    }

    /**
     * Pure resolution core, kept static so it can be unit-tested without a server.
     * Exact names win over prefixes, and ambiguous prefixes are rejected.
     */
    public static Match pick(String input, Collection<Match> candidates) {
        if (input == null) {
            return null;
        }
        String needle = input.trim();
        if (needle.isEmpty()) {
            return null;
        }

        UUID asId = parseUuid(needle);
        if (asId != null) {
            for (Match match : candidates) {
                if (match.id().equals(asId)) {
                    return match;
                }
            }
            return null;
        }

        Match exactOnline = exact(needle, candidates, true, false);
        if (exactOnline != null) {
            return exactOnline;
        }
        Match exactOnlineIgnoreCase = exact(needle, candidates, true, true);
        if (exactOnlineIgnoreCase != null) {
            return exactOnlineIgnoreCase;
        }
        Match exactOffline = exact(needle, candidates, false, false);
        if (exactOffline != null) {
            return exactOffline;
        }
        Match exactOfflineIgnoreCase = exact(needle, candidates, false, true);
        if (exactOfflineIgnoreCase != null) {
            return exactOfflineIgnoreCase;
        }

        Match onlinePrefix = uniquePrefix(needle, candidates, true);
        if (onlinePrefix != null) {
            return onlinePrefix;
        }
        return uniquePrefix(needle, candidates, false);
    }

    private static Match exact(String needle, Collection<Match> candidates,
                               boolean online, boolean ignoreCase) {
        Match found = null;
        for (Match match : candidates) {
            if (match.online() != online || match.name() == null) {
                continue;
            }
            boolean equal = ignoreCase
                    ? match.name().equalsIgnoreCase(needle)
                    : match.name().equals(needle);
            if (!equal) {
                continue;
            }
            if (found != null && !found.id().equals(match.id())) {
                // Duplicate names should never happen online, but never guess offline.
                return null;
            }
            found = match;
        }
        return found;
    }

    private static Match uniquePrefix(String needle, Collection<Match> candidates, boolean online) {
        String lowered = needle.toLowerCase(Locale.ROOT);
        Map<UUID, Match> found = new LinkedHashMap<>();
        for (Match match : candidates) {
            if (match.online() != online || match.name() == null) {
                continue;
            }
            if (match.name().toLowerCase(Locale.ROOT).startsWith(lowered)) {
                found.putIfAbsent(match.id(), match);
            }
        }
        return found.size() == 1 ? found.values().iterator().next() : null;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
