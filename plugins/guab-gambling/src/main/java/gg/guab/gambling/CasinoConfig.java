package gg.guab.gambling;

import org.bukkit.plugin.java.JavaPlugin;

public record CasinoConfig(
        boolean enabled,
        String worldName,
        int x,
        int y,
        int z,
        int spawnX,
        int spawnY,
        int spawnZ) {

    public static CasinoConfig load(JavaPlugin plugin) {
        var cfg = plugin.getConfig();
        return new CasinoConfig(
                cfg.getBoolean("event.enabled", false),
                cfg.getString("venue.world", "guab_casino"),
                cfg.getInt("venue.x", 0),
                cfg.getInt("venue.y", 64),
                cfg.getInt("venue.z", 0),
                cfg.getInt("venue.spawn-x", 0),
                cfg.getInt("venue.spawn-y", 65),
                cfg.getInt("venue.spawn-z", 15));
    }
}
