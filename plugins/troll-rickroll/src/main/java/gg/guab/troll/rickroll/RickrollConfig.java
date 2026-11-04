package gg.guab.troll.rickroll;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public record RickrollConfig(
        double chance,
        long cooldownMillis,
        Set<String> admins,
        String packUrl,
        boolean preload,
        String packFile,
        String worldName,
        int introTicks,
        int fallbackSeconds,
        double screenWidth,
        double screenHeight,
        int showSeconds,
        int fps,
        int frameCount,
        String sha1) {

    public static RickrollConfig load(TrollRickrollPlugin plugin) {
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
        int duration = Math.max(1, parseInt(props.getProperty("duration"), cfg.getInt("show-seconds", 213)));

        List<String> adminNames = cfg.getStringList("admins");
        Set<String> admins = new HashSet<>();
        for (String name : adminNames) {
            admins.add(name.toLowerCase());
        }

        return new RickrollConfig(
                Math.clamp(cfg.getDouble("chance", 0.01), 0.0, 1.0),
                Math.max(0, cfg.getLong("cooldown-seconds", 600)) * 1000L,
                admins,
                cfg.getString("pack.url", ""),
                cfg.getBoolean("pack.preload", true),
                cfg.getString("pack.file", "/resourcepack/rickroll.zip"),
                cfg.getString("cinema.world", "guab_cinema"),
                Math.max(0, cfg.getInt("cinema.intro-ticks", 40)),
                Math.max(1, cfg.getInt("cinema.fallback-seconds", 15)),
                cfg.getDouble("cinema.width", 12.0),
                cfg.getDouble("cinema.height", 6.75),
                duration,
                fps,
                frames,
                props.getProperty("sha1", ""));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public byte[] packHash() {
        if (sha1 == null || sha1.length() < 2) {
            return new byte[0];
        }
        byte[] out = new byte[sha1.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(sha1.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
