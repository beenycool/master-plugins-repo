package gg.guab.marriage;

import gg.guab.core.PlayerPreferencesStore;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * Plays the pack's custom wedding sound, honestly respecting the shared
 * {@code sounds} preference from GuabCore's {@link PlayerPreferencesStore}.
 */
public final class MarriageSounds {

    public static final Key WEDDING_BELL = Key.key("guab", "wedding_bell");

    private MarriageSounds() {
    }

    public static void weddingBell(Player player) {
        play(player, WEDDING_BELL, 1.0f, 1.0f);
    }

    public static void play(Player player, Key key, float volume, float pitch) {
        if (player == null || key == null || !soundsEnabled(player)) {
            return;
        }
        player.playSound(Sound.sound(key, Sound.Source.PLAYER, volume, pitch));
    }

    public static void play(Player player, org.bukkit.Sound sound, SoundCategory category,
                            float volume, float pitch) {
        if (player == null || sound == null || !soundsEnabled(player)) {
            return;
        }
        player.playSound(player.getLocation(), sound, category, volume, pitch);
    }

    static boolean soundsEnabled(Player player) {
        var registration = Bukkit.getServicesManager().getRegistration(PlayerPreferencesStore.class);
        return registration == null || registration.getProvider().sounds(player.getUniqueId());
    }
}
