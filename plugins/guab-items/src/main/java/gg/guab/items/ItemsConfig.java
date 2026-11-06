package gg.guab.items;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Snapshot of config.yml. Values are clamped to safe ranges so a bad edit cannot
 * send players into orbit or create impossible potion effects.
 */
public record ItemsConfig(
        double windChargePower,
        double windChargeVerticalBoost,
        double windChargeRadius,
        double windChargeKnockback,
        double windChargeKnockbackVertical,
        boolean windChargeConsume,
        int windChargeCooldownSeconds,
        int jumpBoostAmplifier,
        int jumpBoostCooldownSeconds,
        int maxGiveAmount) {

    public static ItemsConfig load(GuabItemsPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();
        return new ItemsConfig(
                Math.clamp(cfg.getDouble("wind-charge.power", 1.8), 0.0, 10.0),
                Math.clamp(cfg.getDouble("wind-charge.vertical-boost", 0.6), -2.0, 5.0),
                Math.clamp(cfg.getDouble("wind-charge.radius", 5.0), 0.0, 32.0),
                Math.clamp(cfg.getDouble("wind-charge.knockback", 1.2), 0.0, 10.0),
                Math.clamp(cfg.getDouble("wind-charge.knockback-vertical", 0.35), -2.0, 5.0),
                cfg.getBoolean("wind-charge.consume", true),
                Math.max(0, cfg.getInt("wind-charge.cooldown-seconds", 8)),
                Math.clamp(cfg.getInt("jump-boost.amplifier", 2), 0, 255),
                Math.max(0, cfg.getInt("jump-boost.cooldown-seconds", 30)),
                Math.clamp(cfg.getInt("command.max-give-amount", 64), 1, 2304));
    }

    public long windChargeCooldownMillis() {
        return windChargeCooldownSeconds * 1000L;
    }

    public long jumpBoostCooldownMillis() {
        return jumpBoostCooldownSeconds * 1000L;
    }
}
