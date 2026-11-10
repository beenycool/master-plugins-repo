package gg.guab.core;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small per-player preference store. Each player gets their own YAML file under
 * {@code plugins/GuabCore/preferences/<uuid>.yml}; toggles are written immediately
 * using a temp-file swap so a server crash cannot leave a half-written file behind.
 */
public final class PlayerPreferencesStore {

    public enum Toggle {
        SIDEBAR("sidebar", true),
        TAB("tab", true),
        NAMETAG("nametag", true),
        SOUNDS("sounds", true),
        ACTIONBAR("actionbar", true),
        TITLES("titles", true),
        RICKROLL_REDUCED_MOTION("rickroll-reduced-motion", false),
        RICKROLL_OPT_OUT("rickroll-opt-out", false);

        private final String key;
        private final boolean defaultValue;

        Toggle(String key, boolean defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public String key() {
            return key;
        }

        public boolean defaultValue() {
            return defaultValue;
        }

        /** Parses a command token. Supports a couple of friendly aliases. */
        public static Toggle parse(String raw) {
            if (raw == null) {
                return null;
            }
            String value = raw.toLowerCase(Locale.ROOT).replace('_', '-');
            return switch (value) {
                case "sidebar", "scoreboard" -> SIDEBAR;
                case "tab", "tablist", "tab-list" -> TAB;
                case "nametag", "name-tag", "belowname", "below-name" -> NAMETAG;
                case "sound", "sounds" -> SOUNDS;
                case "actionbar", "action-bar" -> ACTIONBAR;
                case "title", "titles", "arrival-titles" -> TITLES;
                case "rickroll-motion", "reduced-motion", "motion" -> RICKROLL_REDUCED_MOTION;
                case "rickroll", "rickroll-optout", "rickroll-opt-out", "optout", "opt-out" ->
                        RICKROLL_OPT_OUT;
                default -> null;
            };
        }
    }

    private final Path folder;
    private final Map<UUID, Preferences> cache = new ConcurrentHashMap<>();
    private final Object ioLock = new Object();

    public PlayerPreferencesStore(Path folder) {
        this.folder = folder;
    }

    public Path folder() {
        return folder;
    }

    /** Returns a defensive snapshot for reading. */
    public Preferences snapshot(UUID player) {
        return get(player).copy();
    }

    public boolean get(UUID player, Toggle toggle) {
        return get(player).get(toggle);
    }

    public boolean sidebar(UUID player) {
        return get(player, Toggle.SIDEBAR);
    }

    public boolean tab(UUID player) {
        return get(player, Toggle.TAB);
    }

    public boolean nametag(UUID player) {
        return get(player, Toggle.NAMETAG);
    }

    public boolean sounds(UUID player) {
        return get(player, Toggle.SOUNDS);
    }

    public boolean actionbar(UUID player) {
        return get(player, Toggle.ACTIONBAR);
    }

    public boolean titles(UUID player) {
        return get(player, Toggle.TITLES);
    }

    public boolean rickrollReducedMotion(UUID player) {
        return get(player, Toggle.RICKROLL_REDUCED_MOTION);
    }

    public boolean rickrollOptOut(UUID player) {
        return get(player, Toggle.RICKROLL_OPT_OUT);
    }

    /** Applies a value and writes the file. Returns the new value. */
    public boolean set(UUID player, Toggle toggle, boolean value) {
        Preferences preferences = get(player);
        synchronized (preferences) {
            preferences.values.put(toggle, value);
        }
        save(player, preferences);
        return value;
    }

    /** Flips a toggle, persists it and returns the new value. */
    public boolean toggle(UUID player, Toggle toggle) {
        return set(player, toggle, !get(player, toggle));
    }

    /** Persists every cached player. Called on plugin shutdown. */
    public void saveAll() {
        for (Map.Entry<UUID, Preferences> entry : cache.entrySet()) {
            save(entry.getKey(), entry.getValue());
        }
    }

    private Preferences get(UUID player) {
        Preferences existing = cache.get(player);
        if (existing != null) {
            return existing;
        }
        return cache.computeIfAbsent(player, this::load);
    }

    private Preferences load(UUID player) {
        Preferences preferences = new Preferences();
        Path file = fileFor(player);
        if (!Files.isRegularFile(file)) {
            return preferences;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        for (Toggle toggle : Toggle.values()) {
            String path = "prefs." + toggle.key();
            if (yaml.isSet(path)) {
                preferences.values.put(toggle, yaml.getBoolean(path, toggle.defaultValue()));
            }
        }
        return preferences;
    }

    private void save(UUID player, Preferences preferences) {
        Map<Toggle, Boolean> copy;
        synchronized (preferences) {
            copy = new EnumMap<>(preferences.values);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("uuid", player.toString());
        for (Map.Entry<Toggle, Boolean> entry : copy.entrySet()) {
            yaml.set("prefs." + entry.getKey().key(), entry.getValue());
        }
        Path file = fileFor(player);
        synchronized (ioLock) {
            try {
                Files.createDirectories(folder);
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
                moveAtomically(temp, file);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not save preferences for " + player, exception);
            }
        }
    }

    private static void moveAtomically(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path fileFor(UUID player) {
        return folder.resolve(player + ".yml");
    }

    /** Mutable preference set owned by the store. Read through {@link #snapshot(UUID)}. */
    public static final class Preferences {

        private final EnumMap<Toggle, Boolean> values = new EnumMap<>(Toggle.class);

        private Preferences() {
            for (Toggle toggle : Toggle.values()) {
                values.put(toggle, toggle.defaultValue());
            }
        }

        public boolean get(Toggle toggle) {
            return values.getOrDefault(toggle, toggle.defaultValue());
        }

        public Preferences copy() {
            Preferences copy = new Preferences();
            synchronized (this) {
                copy.values.putAll(values);
            }
            return copy;
        }
    }
}
