package gg.guab.core;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * YAML-backed points ledger. Balances are cached in memory and flushed on a timer,
 * so a busy casino night never touches the disk on the game thread.
 */
public final class PointsStore implements PointsService {

    private final Path file;
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private volatile boolean loadFailed;
    private volatile String loadError;

    public PointsStore(Path file) {
        this.file = file;
    }

    /**
     * Loads the ledger fail-safe: a non-empty file that cannot be understood is
     * preserved (and copied to {@code .bak}) instead of being silently replaced by
     * an empty ledger on the next save.
     */
    public void load() {
        loadFailed = false;
        loadError = null;
        if (!Files.isRegularFile(file)) {
            balances.clear();
            return;
        }
        Map<UUID, Long> parsed = new HashMap<>();
        try {
            if (Files.size(file) == 0) {
                balances.clear();
                return;
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
            ConfigurationSection section = yaml.getConfigurationSection("players");
            if (section == null) {
                failLoad("no 'players' section");
                return;
            }
            for (String key : section.getKeys(false)) {
                try {
                    parsed.put(UUID.fromString(key), Math.max(0L, section.getLong(key, 0L)));
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed keys instead of failing the whole ledger.
                }
            }
        } catch (RuntimeException | IOException exception) {
            failLoad(exception.getMessage() == null ? exception.getClass().getSimpleName()
                    : exception.getMessage());
            return;
        }
        balances.clear();
        balances.putAll(parsed);
    }

    private void failLoad(String reason) {
        loadFailed = true;
        loadError = reason;
        try {
            backup();
        } catch (IOException ignored) {
            // The original file is still untouched; a later save will try again.
        }
    }

    public boolean loadFailed() {
        return loadFailed;
    }

    public String loadError() {
        return loadError;
    }

    @Override
    public long get(UUID player) {
        return balances.getOrDefault(player, 0L);
    }

    @Override
    public void set(UUID player, long amount) {
        long before = get(player);
        long after = Math.max(0L, amount);
        balances.put(player, after);
        fireChange(player, before, after);
    }

    @Override
    public long add(UUID player, long amount) {
        if (amount == 0) {
            return get(player);
        }
        long[] before = new long[1];
        long after = balances.compute(player, (key, current) -> {
            before[0] = current == null ? 0L : current;
            long next = before[0] + amount;
            if (amount > 0L && next < before[0]) {
                return Long.MAX_VALUE;
            }
            if (next <= 0L) {
                return 0L;
            }
            return next;
        });
        fireChange(player, before[0], after);
        return after;
    }

    @Override
    public boolean take(UUID player, long amount) {
        if (amount <= 0) {
            return false;
        }
        boolean[] taken = { false };
        long[] before = new long[1];
        balances.compute(player, (key, current) -> {
            long balance = current == null ? 0L : current;
            before[0] = balance;
            if (balance < amount) {
                return balance;
            }
            taken[0] = true;
            return balance - amount;
        });
        if (taken[0]) {
            fireChange(player, before[0], before[0] - amount);
        }
        return taken[0];
    }

    @Override
    public boolean transfer(UUID from, UUID to, long amount) {
        if (from == null || to == null || from.equals(to) || amount <= 0) {
            return false;
        }
        boolean[] moved = { false };
        long[] fromBefore = new long[1];
        long fromAfter = balances.compute(from, (key, current) -> {
            long balance = current == null ? 0L : current;
            fromBefore[0] = balance;
            if (balance < amount) {
                return balance;
            }
            moved[0] = true;
            return balance - amount;
        });
        if (!moved[0]) {
            return false;
        }
        long[] toBefore = new long[1];
        long toAfter = balances.compute(to, (key, current) -> {
            long balance = current == null ? 0L : current;
            toBefore[0] = balance;
            long next = balance + amount;
            return next < balance ? Long.MAX_VALUE : next;
        });
        fireChange(from, fromBefore[0], fromAfter);
        fireChange(to, toBefore[0], toAfter);
        return true;
    }

    @Override
    public Set<UUID> knownPlayers() {
        return Set.copyOf(balances.keySet());
    }

    private static void fireChange(UUID player, long before, long after) {
        if (before == after || Bukkit.getServer() == null) {
            return;
        }
        var manager = Bukkit.getPluginManager();
        if (manager == null) {
            return;
        }
        manager.callEvent(new GuabPointsChangeEvent(player, before, after));
    }

    @Override
    public List<Map.Entry<UUID, Long>> top(int limit) {
        int wanted = Math.max(1, limit);
        return balances.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().toString()))
                .limit(wanted)
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    public int size() {
        return balances.size();
    }

    /** Write a snapshot to disk. Safe to call from an async task. */
    public void save() {
        Map<UUID, Long> snapshot = new HashMap<>(balances);
        if (loadFailed && snapshot.isEmpty()) {
            throw new IllegalStateException("Refusing to overwrite unreadable "
                    + file.getFileName() + ": " + loadError);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        if (snapshot.isEmpty()) {
            yaml.createSection("players");
        } else {
            for (Map.Entry<UUID, Long> entry : snapshot.entrySet()) {
                yaml.set("players." + entry.getKey(), entry.getValue());
            }
        }
        synchronized (writeLock) {
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                backup();
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not save " + file.getFileName(), exception);
            }
        }
    }

    /** Keeps one previous revision next to the live ledger. */
    private void backup() throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Path bak = file.resolveSibling(file.getFileName() + ".bak");
        Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
    }
}
