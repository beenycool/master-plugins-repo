package gg.guab.blocks;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

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
            PotionEffectType type = resolveEffect(name, plugin.getLogger());
            if (type == null) {
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
                resolveSound(cfg.getString("fake.break-sound", "block.glass.break"),
                        Sound.BLOCK_GLASS_BREAK, plugin.getLogger()),
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
        return resolveEffect(name, null);
    }

    public static PotionEffectType resolveEffect(String name, Logger logger) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String id = name.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        try {
            PotionEffectType type = Registry.EFFECT.get(NamespacedKey.fromString(id));
            if (type == null && logger != null) {
                logger.warning("Unknown potion effect '" + name + "'; using the configured effect list.");
            }
            return type;
        } catch (IllegalArgumentException exception) {
            if (logger != null) {
                logger.warning("Invalid potion effect key '" + name + "'; using the configured effect list.");
            }
            return null;
        }
    }

    public static Sound resolveSound(String name, Sound fallback) {
        return resolveSound(name, fallback, null);
    }

    public static Sound resolveSound(String name, Sound fallback, Logger logger) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Sound direct = enumSound(name);
        if (direct != null) {
            return direct;
        }
        String key = soundKey(name);
        if (key == null) {
            return fallback;
        }
        try {
            Sound sound = Registry.SOUNDS.get(NamespacedKey.fromString(key));
            if (sound == null) {
                if (logger != null) {
                    logger.warning("Unknown sound '" + name + "' (resolved to " + key
                            + "); falling back to " + fallback.key().asString() + ".");
                }
                return fallback;
            }
            return sound;
        } catch (IllegalArgumentException exception) {
            if (logger != null) {
                logger.warning("Invalid sound key '" + name + "' (resolved to " + key
                        + "); falling back to " + fallback.key().asString() + ".");
            }
            return fallback;
        }
    }

    public static boolean isKnownSound(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (enumSound(name) != null) {
            return true;
        }
        String key = soundKey(name);
        if (key == null) {
            return false;
        }
        try {
            return Registry.SOUNDS.get(NamespacedKey.fromString(key)) != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Normalizes a sound key without destroying the underscores that are part of
     * Minecraft sound names. For example {@code block.note_block.hat} must stay
     * exactly that, not become {@code block.note.block.hat}.
     */
    public static String soundKey(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String raw = name.trim();
        if (raw.contains(":") || raw.contains(".")) {
            String id = raw.toLowerCase(Locale.ROOT);
            return id.contains(":") ? id : "minecraft:" + id;
        }
        Sound sound = enumSound(raw);
        if (sound != null) {
            return sound.key().asString();
        }
        String id = raw.toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static Sound enumSound(String name) {
        try {
            return Sound.valueOf(name.trim().toUpperCase(Locale.ROOT).replace('.', '_'));
        } catch (IllegalArgumentException | LinkageError exception) {
            return null;
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
