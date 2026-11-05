package gg.guab.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * YAML-backed points ledger. Balances are cached in memory and flushed on a timer,
 * so a busy casino night never touches the disk on the game thread.
 */
public final class PointsStore implements PointsService {

    private final Path file;
    private final Map<UUID, Integer> balances = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public PointsStore(Path file) {
        this.file = file;
    }

    public void load() {
        balances.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                balances.put(UUID.fromString(key), Math.max(0, section.getInt(key, 0)));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed keys instead of failing the whole ledger.
            }
        }
    }

    @Override
    public int get(UUID player) {
        return balances.getOrDefault(player, 0);
    }

    @Override
    public void set(UUID player, int amount) {
        balances.put(player, Math.max(0, amount));
    }

    @Override
    public int add(UUID player, int amount) {
        if (amount == 0) {
            return get(player);
        }
        return balances.merge(player, amount, (current, delta) -> {
            long next = (long) current + delta;
            if (next <= 0) {
                return 0;
            }
            return next > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) next;
        });
    }

    @Override
    public boolean take(UUID player, int amount) {
        if (amount <= 0) {
            return false;
        }
        boolean[] taken = { false };
        balances.compute(player, (key, current) -> {
            int balance = current == null ? 0 : current;
            if (balance < amount) {
                return balance;
            }
            taken[0] = true;
            return balance - amount;
        });
        return taken[0];
    }

    public int size() {
        return balances.size();
    }

    /** Write a snapshot to disk. Safe to call from an async task. */
    public void save() {
        Map<UUID, Integer> snapshot = new HashMap<>(balances);
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> entry : snapshot.entrySet()) {
            yaml.set("players." + entry.getKey(), entry.getValue());
        }
        synchronized (writeLock) {
            try {
                Files.createDirectories(file.getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not save " + file.getFileName(), exception);
            }
        }
    }
}
