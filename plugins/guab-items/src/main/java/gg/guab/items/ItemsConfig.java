package gg.guab.items;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        int beerCooldownSeconds,
        int beerTapCooldownSeconds,
        double beerConsumeSeconds,
        boolean beerReturnEmptyBottle,
        List<BeerEffect> beerEffects,
        int maxGiveAmount) {

    public record BeerEffect(PotionEffectType type, int durationTicks, int amplifier) {
    }

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
                Math.max(0, cfg.getInt("beer.cooldown-seconds", 2)),
                Math.max(0, cfg.getInt("beer.tap-cooldown-seconds", 1)),
                Math.clamp(cfg.getDouble("beer.consume-seconds", 1.1), 0.2, 5.0),
                cfg.getBoolean("beer.return-empty-bottle", false),
                loadBeerEffects(cfg),
                Math.clamp(cfg.getInt("command.max-give-amount", 64), 1, 2304));
    }

    private static List<BeerEffect> loadBeerEffects(FileConfiguration cfg) {
        // Empty means no instant effects: the drunk meter applies the tiers.
        return List.copyOf(parseEffects(cfg.getList("beer.effects")));
    }

    public static List<BeerEffect> parseEffects(List<?> raw) {
        List<BeerEffect> effects = new ArrayList<>();
        if (raw == null) {
            return effects;
        }
        for (Object entry : raw) {
            if (entry instanceof ConfigurationSection section) {
                effect(section.getString("type"),
                        section.getInt("duration-seconds", 8),
                        section.getInt("amplifier", 0)).ifPresent(effects::add);
            } else if (entry instanceof java.util.Map<?, ?> map) {
                effect(string(map.get("type")),
                        number(map.get("duration-seconds"), 8),
                        number(map.get("amplifier"), 0)).ifPresent(effects::add);
            }
        }
        return effects;
    }

    private static java.util.Optional<BeerEffect> effect(String name, int durationSeconds, int amplifier) {
        PotionEffectType type = resolveEffect(name);
        if (type == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new BeerEffect(
                type,
                Math.max(1, durationSeconds) * 20,
                Math.clamp(amplifier, 0, 255)));
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public static PotionEffectType resolveEffect(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String id = name.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        try {
            return Registry.MOB_EFFECT.get(NamespacedKey.fromString(id));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public long beerCooldownMillis() {
        return beerCooldownSeconds * 1000L;
    }

    public long windChargeCooldownMillis() {
        return windChargeCooldownSeconds * 1000L;
    }

    public long jumpBoostCooldownMillis() {
        return jumpBoostCooldownSeconds * 1000L;
    }
}
