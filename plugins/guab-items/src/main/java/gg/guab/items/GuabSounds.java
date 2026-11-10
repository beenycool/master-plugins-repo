package gg.guab.items;

import gg.guab.core.PlayerPreferencesStore;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * Custom sound events shipped in both Guab resource packs. The keys must match
 * the entries in {@code assets/guab/sounds.json}; the pack builder verifies the
 * referenced OGG files are present.
 */
final class GuabSounds {

    static final Key QUIZ_CORRECT = Key.key("guab", "quiz_correct");
    static final Key ONE_EIGHTY = Key.key("guab", "one_eighty");
    static final Key BEER_POUR = Key.key("guab", "beer_pour");
    static final Key MILESTONE = Key.key("guab", "milestone");

    private GuabSounds() {
    }

    static void play(Player player, Key key, float volume, float pitch) {
        if (!enabled(player)) {
            return;
        }
        player.playSound(Sound.sound(key, Sound.Source.PLAYER, volume, pitch));
    }

    /** Bukkit sound overload that respects the player's sound preference. */
    static void play(Player player, org.bukkit.Sound sound, SoundCategory category,
                     float volume, float pitch) {
        if (!enabled(player)) {
            return;
        }
        player.playSound(player.getLocation(), sound, category, volume, pitch);
    }

    static void play(Player player, org.bukkit.Sound sound, float volume, float pitch) {
        if (!enabled(player)) {
            return;
        }
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private static boolean enabled(Player player) {
        var registration = Bukkit.getServicesManager()
                .getRegistration(PlayerPreferencesStore.class);
        return registration == null || registration.getProvider().sounds(player.getUniqueId());
    }
}
