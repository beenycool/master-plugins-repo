package gg.guab.rickroll;

import io.papermc.paper.entity.PlayerPostEffects;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies and restores Paper's client-side player post effects for a show.
 *
 * <p>Every call into {@link PlayerPostEffects} is guarded: an unsupported key, an older server or any other API
 * failure only logs a warning and lets the show carry on without effects.</p>
 */
final class PostEffects {

    private static final String DEFAULT_BLUR = "minecraft:blur";
    private static final String DEFAULT_FLASH = "minecraft:invert";

    private final GuabRickrollPlugin plugin;
    private final Player player;

    private List<Key> original = List.of();
    private Key flashKey;
    private BukkitTask flashTask;
    private boolean captured;
    private boolean changed;
    private boolean unavailable;

    PostEffects(GuabRickrollPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    void apply() {
        RickrollConfig config = plugin.config();
        if (!config.postEffectsEnabled() || unavailable || !player.isOnline()) {
            return;
        }

        try {
            PlayerPostEffects effects = player.postEffects();
            if (!captured) {
                original = List.copyOf(effects.values());
                captured = true;
            }

            Key blur = parse(config.blurKey());
            if (blur != null) {
                add(effects, blur, false);
            }

            if (config.flashTicks() > 0) {
                Key flash = parse(config.flashKey());
                if (flash != null && add(effects, flash, true)) {
                    flashKey = flash;
                    flashTask = Bukkit.getScheduler().runTaskLater(plugin, this::removeFlash, config.flashTicks());
                }
            }
            effects.update();
        } catch (Throwable throwable) {
            unavailable = true;
            plugin.getLogger().warning("Cinematic post effects are unavailable for " + player.getName()
                    + " (" + describe(throwable) + "); continuing without them.");
        }
    }

    void reload() {
        if (!plugin.config().postEffectsEnabled()) {
            stop();
            return;
        }
        if (changed) {
            stop();
        }
        apply();
    }

    void stop() {
        if (flashTask != null) {
            flashTask.cancel();
            flashTask = null;
        }
        flashKey = null;

        if (!captured || !changed) {
            reset();
            return;
        }

        try {
            PlayerPostEffects effects = player.postEffects();
            if (original.isEmpty()) {
                effects.clear();
            } else {
                effects.set(new ArrayList<>(original));
            }
            effects.update();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not restore cinematic post effects for " + player.getName()
                    + " (" + describe(throwable) + "); clearing them instead.");
            try {
                if (player.isOnline()) {
                    player.postEffects().clear();
                }
            } catch (Throwable ignored) {
                // The player is leaving or the API is unavailable; there is nothing more we can do.
            }
        } finally {
            reset();
        }
    }

    /**
     * Removes only the effects this plugin manages. Used when repairing a player left behind by a crash or reload.
     */
    static void clear(GuabRickrollPlugin plugin, Player player) {
        RickrollConfig config = plugin.config();
        Set<String> keys = new LinkedHashSet<>();
        keys.add(DEFAULT_BLUR);
        keys.add(DEFAULT_FLASH);
        if (config.blurKey() != null && !config.blurKey().isBlank()) {
            keys.add(config.blurKey().trim());
        }
        if (config.flashKey() != null && !config.flashKey().isBlank()) {
            keys.add(config.flashKey().trim());
        }

        try {
            PlayerPostEffects effects = player.postEffects();
            for (String raw : keys) {
                try {
                    effects.remove(Key.key(raw));
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Ignoring invalid cinematic post effect key '" + raw + "': "
                            + describe(exception));
                }
            }
            effects.update();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not clear stale cinematic effects for " + player.getName()
                    + " (" + describe(throwable) + ").");
        }
    }

    private boolean add(PlayerPostEffects effects, Key key, boolean flash) {
        try {
            if (effects.values().contains(key)) {
                return false;
            }
            if (!effects.add(key)) {
                plugin.getLogger().warning("Cinematic post effect '" + key.asString() + "' was not applied"
                        + (flash ? " as the VHS flash" : "") + "; the server may not support it.");
                return false;
            }
            changed = true;
            return true;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Cinematic post effect '" + key.asString() + "' is not supported"
                    + (flash ? " as the VHS flash" : "") + ": " + describe(throwable));
            return false;
        }
    }

    private Key parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Key.key(raw.trim());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Ignoring invalid cinematic post effect key '" + raw + "': "
                    + describe(exception));
            return null;
        }
    }

    private void removeFlash() {
        flashTask = null;
        Key key = flashKey;
        flashKey = null;
        if (key == null) {
            return;
        }
        try {
            if (player.isOnline()) {
                player.postEffects().remove(key);
                player.postEffects().update();
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not clear the cinematic VHS flash for " + player.getName()
                    + " (" + describe(throwable) + ").");
        }
    }

    private void reset() {
        captured = false;
        changed = false;
        original = List.of();
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
