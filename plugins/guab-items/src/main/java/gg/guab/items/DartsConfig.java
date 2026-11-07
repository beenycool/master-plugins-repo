package gg.guab.items;

import org.bukkit.block.BlockFace;

import java.util.Locale;

/**
 * Snapshot of the darts configuration. Every value is clamped so a bad edit
 * cannot make the board unplayable or turn a leg into an instant win.
 */
public record DartsConfig(
        boolean enabled,
        String world,
        int x,
        int y,
        int z,
        BlockFace face,
        double boardRadius,
        long cooldownMillis,
        double maxDistance,
        int defaultMode,
        int legsToWin,
        boolean beginnerAssist,
        int hudIntervalTicks,
        double wobbleSober,
        double wobblePerDrunk,
        double wobbleMax,
        double bull,
        double outerBull,
        double trebleInner,
        double trebleOuter,
        double doubleInner,
        double doubleOuter,
        boolean rewards,
        int base101,
        int base301,
        int base501,
        int par101,
        int par301,
        int par501,
        int underParPerDart,
        double checkoutPerPoint,
        int maxReward) {

    public static DartsConfig load(GuabItemsPlugin plugin) {
        var cfg = plugin.getConfig();

        double bull = clamp(cfg.getDouble("pub.darts.rings.bull", 0.07), 0.01, 0.40);
        double outerBull = clamp(cfg.getDouble("pub.darts.rings.outer-bull", 0.15), bull + 0.01, 0.80);
        double trebleInner = clamp(cfg.getDouble("pub.darts.rings.treble-inner", 0.52), outerBull + 0.01, 0.95);
        double trebleOuter = clamp(cfg.getDouble("pub.darts.rings.treble-outer", 0.68), trebleInner + 0.01, 0.98);
        double doubleInner = clamp(cfg.getDouble("pub.darts.rings.double-inner", 0.90), trebleOuter + 0.01, 1.0);

        int mode = cfg.getInt("pub.darts.default-mode", 101);
        if (mode != 101 && mode != 301 && mode != 501) {
            mode = 501;
        }

        return new DartsConfig(
                cfg.getBoolean("pub.darts.enabled", true),
                cfg.getString("pub.darts.board.world", "guab_casino"),
                cfg.getInt("pub.darts.board.x", -24),
                cfg.getInt("pub.darts.board.y", 66),
                cfg.getInt("pub.darts.board.z", -12),
                parseFace(cfg.getString("pub.darts.board.face", "EAST")),
                clamp(cfg.getDouble("pub.darts.board.radius", 0.5), 0.1, 4.0),
                (long) (clamp(cfg.getDouble("pub.darts.cooldown-seconds", 0.35), 0.1, 5.0) * 1000.0),
                clamp(cfg.getDouble("pub.darts.max-distance", 8.0), 1.0, 32.0),
                mode,
                clamp(cfg.getInt("pub.darts.legs-to-win", 1), 1, 9),
                cfg.getBoolean("pub.darts.beginner-assist", true),
                clamp(cfg.getInt("pub.darts.hud-interval-ticks", 10), 4, 100),
                clamp(cfg.getDouble("pub.darts.wobble.sober", 0.008), 0.0, 0.5),
                clamp(cfg.getDouble("pub.darts.wobble.per-drunk-level", 0.012), 0.0, 0.5),
                clamp(cfg.getDouble("pub.darts.wobble.max", 0.25), 0.0, 0.5),
                bull,
                outerBull,
                trebleInner,
                trebleOuter,
                doubleInner,
                1.0,
                cfg.getBoolean("pub.darts.rewards.enabled", true),
                Math.max(0, cfg.getInt("pub.darts.rewards.base-101", 20)),
                Math.max(0, cfg.getInt("pub.darts.rewards.base-301", 60)),
                Math.max(0, cfg.getInt("pub.darts.rewards.base-501", 100)),
                Math.max(1, cfg.getInt("pub.darts.rewards.par-darts-101", 9)),
                Math.max(1, cfg.getInt("pub.darts.rewards.par-darts-301", 15)),
                Math.max(1, cfg.getInt("pub.darts.rewards.par-darts-501", 21)),
                Math.max(0, cfg.getInt("pub.darts.rewards.under-par-per-dart", 2)),
                clamp(cfg.getDouble("pub.darts.rewards.checkout-per-point", 0.1), 0.0, 10.0),
                Math.max(1, cfg.getInt("pub.darts.rewards.max", 300)));
    }

    private static BlockFace parseFace(String raw) {
        if (raw == null) {
            return BlockFace.EAST;
        }
        try {
            BlockFace parsed = BlockFace.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return switch (parsed) {
                case NORTH, EAST, SOUTH, WEST -> parsed;
                default -> BlockFace.EAST;
            };
        } catch (IllegalArgumentException ignored) {
            return BlockFace.EAST;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
