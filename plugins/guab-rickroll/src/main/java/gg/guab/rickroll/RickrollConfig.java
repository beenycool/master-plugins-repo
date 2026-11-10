package gg.guab.rickroll;

import org.bukkit.GameMode;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public record RickrollConfig(
        double chance,
        long cooldownMillis,
        boolean packEnabled,
        String packUrl,
        boolean preload,
        int packTimeoutSeconds,
        String packFile,
        String worldName,
        int introTicks,
        int fallbackSeconds,
        double screenWidth,
        double screenHeight,
        int showSeconds,
        double lyricsOffsetSeconds,
        int fps,
        int frameCount,
        String sha1,
        boolean postEffectsEnabled,
        String blurKey,
        String flashKey,
        int flashTicks,
        boolean cameraIntroEnabled,
        int cameraIntroDurationTicks,
        boolean paused,
        List<String> ignoreWorlds,
        Set<GameMode> ignoreGameModes,
        int joinGraceSeconds,
        int teleportGraceSeconds,
        boolean ignoreVehicles,
        boolean ignoreGliding) {

    public RickrollConfig {
        ignoreWorlds = ignoreWorlds == null ? List.of() : List.copyOf(ignoreWorlds);
        ignoreGameModes = ignoreGameModes == null ? Set.of() : Set.copyOf(ignoreGameModes);
    }

    public static RickrollConfig load(GuabRickrollPlugin plugin) {
        var cfg = plugin.getConfig();

        Properties props = new Properties();
        Path propsPath = plugin.getDataFolder().toPath().resolve("pack.properties");
        if (Files.isRegularFile(propsPath)) {
            try (Reader reader = Files.newBufferedReader(propsPath)) {
                props.load(reader);
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not read pack.properties: " + exception.getMessage());
            }
        }

        int fps = Math.clamp(parseInt(props.getProperty("fps"), cfg.getInt("pack.fps", 10)), 1, 20);
        int frames = Math.max(1, parseInt(props.getProperty("frames"), 1));
        int duration = Math.max(1, parseInt(props.getProperty("duration"), cfg.getInt("show-seconds", 25)));

        List<String> ignoreWorlds = cfg.isSet("trigger.ignore-worlds")
                ? cfg.getStringList("trigger.ignore-worlds")
                : List.of("guab_darts", "guab_casino", "guab_cinema");

        return new RickrollConfig(
                Math.clamp(cfg.getDouble("chance", 0.05), 0.0, 1.0),
                Math.max(0, cfg.getLong("cooldown-seconds", 300)) * 1000L,
                cfg.getBoolean("pack.enabled", true),
                cfg.getString("pack.url", ""),
                cfg.getBoolean("pack.preload", true),
                Math.max(1, cfg.getInt("pack.timeout-seconds", 180)),
                cfg.getString("pack.file", "/resourcepack/rickroll.zip"),
                cfg.getString("cinema.world", "guab_cinema"),
                Math.max(0, cfg.getInt("cinema.intro-ticks", 40)),
                Math.max(1, cfg.getInt("cinema.fallback-seconds", 15)),
                cfg.getDouble("cinema.width", 20.0),
                cfg.getDouble("cinema.height", 11.25),
                duration,
                cfg.getDouble("lyrics-offset-seconds", 0.0),
                fps,
                frames,
                props.getProperty("sha1", ""),
                cfg.getBoolean("cinema.post-effects.enabled", true),
                cfg.getString("cinema.post-effects.blur-key", ""),
                cfg.getString("cinema.post-effects.flash-key", "minecraft:invert"),
                Math.clamp(cfg.getInt("cinema.post-effects.flash-ticks", 8), 0, 40),
                cfg.getBoolean("cinema.camera-intro.enabled", true),
                Math.clamp(cfg.getInt("cinema.camera-intro.duration-ticks", 40), 1, 120),
                cfg.getBoolean("trigger.paused", false),
                ignoreWorlds,
                parseGameModes(plugin, cfg.getStringList("trigger.ignore-gamemodes")),
                Math.max(0, cfg.getInt("trigger.join-grace-seconds", 30)),
                Math.max(0, cfg.getInt("trigger.teleport-grace-seconds", 5)),
                cfg.getBoolean("trigger.ignore-vehicles", true),
                cfg.getBoolean("trigger.ignore-gliding", true));
    }

    private static Set<GameMode> parseGameModes(GuabRickrollPlugin plugin, List<String> raw) {
        List<String> names = raw.isEmpty()
                ? List.of("SPECTATOR", "CREATIVE")
                : raw;
        List<GameMode> modes = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                modes.add(GameMode.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring unknown trigger.ignore-gamemodes entry '" + name + "'.");
            }
        }
        return Set.copyOf(modes);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** True when the configured rickroll pack can actually be requested. */
    public boolean packUsable() {
        return packEnabled && packUrl != null && !packUrl.isBlank();
    }

    /** The chance a natural trigger actually shows, accounting for the pause switch. */
    public double effectiveChance() {
        return paused ? 0.0 : chance;
    }

    public byte[] packHash() {
        return packHash(sha1);
    }

    /**
     * Parses a hexadecimal SHA-1 into the byte array Paper expects. Invalid input (odd length, non-hex, blank)
     * returns an empty array instead of throwing while a player is being sent a pack.
     */
    public static byte[] packHash(String sha1) {
        if (sha1 == null) {
            return new byte[0];
        }
        String value = sha1.trim();
        if (value.length() < 2 || value.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return new byte[0];
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }
}
