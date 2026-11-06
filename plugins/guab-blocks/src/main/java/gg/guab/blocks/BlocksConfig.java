package gg.guab.blocks;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record BlocksConfig(
        boolean enabled,
        double fakeChance,
        int fakeRestoreDelayTicks,
        Sound fakeBreakSound,
        boolean restoreOnDisable,
        List<PotionSpec> potionEffects,
        int swapCooldownSeconds,
        int swapExcludeRadius,
        int perPlayerCooldownSeconds,
        int perBlockCooldownSeconds) {

    public static BlocksConfig load(GuabBlocksPlugin plugin) {
        var cfg = plugin.getConfig();

        List<PotionSpec> effects = new ArrayList<>();
        for (Map<?, ?> raw : cfg.getMapList("potion.effects")) {
            String name = string(raw.get("type"), "");
            PotionEffectType type = resolveEffect(name);
            if (type == null) {
                plugin.getLogger().warning("Unknown potion effect '" + name + "' in config.yml, skipping.");
                continue;
            }
            int seconds = Math.max(1, integer(raw.get("duration-seconds"), 5));
            int amplifier = Math.clamp(integer(raw.get("amplifier"), 0), 0, 255);
            effects.add(new PotionSpec(type, seconds * 20, amplifier));
        }
        if (effects.isEmpty()) {
            plugin.getLogger().warning("potion.effects is empty; potion blocks will do nothing.");
        }

        return new BlocksConfig(
                cfg.getBoolean("enabled", true),
                Math.clamp(cfg.getDouble("fake.chance", 0.6), 0.0, 1.0),
                Math.max(1, cfg.getInt("fake.restore-delay-ticks", 60)),
                resolveSound(cfg.getString("fake.break-sound", "block.glass.break"), Sound.BLOCK_GLASS_BREAK),
                cfg.getBoolean("fake.restore-on-disable", true),
                List.copyOf(effects),
                Math.max(0, cfg.getInt("swap.cooldown-seconds", 30)),
                Math.max(0, cfg.getInt("swap.exclude-radius", 16)),
                Math.max(0, cfg.getInt("cooldowns.per-player-seconds", 5)),
                Math.max(0, cfg.getInt("cooldowns.per-block-seconds", 10)));
    }

    public long perPlayerCooldownMillis() {
        return perPlayerCooldownSeconds * 1000L;
    }

    public long perBlockCooldownMillis() {
        return perBlockCooldownSeconds * 1000L;
    }

    public long swapCooldownMillis() {
        return swapCooldownSeconds * 1000L;
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
            return Registry.EFFECT.get(NamespacedKey.fromString(id));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static Sound resolveSound(String name, Sound fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String id = name.trim().toLowerCase(Locale.ROOT);
        if (!id.contains(":")) {
            id = "minecraft:" + id.replace('_', '.');
        }
        try {
            Sound sound = Registry.SOUNDS.get(NamespacedKey.fromString(id));
            return sound != null ? sound : fallback;
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
