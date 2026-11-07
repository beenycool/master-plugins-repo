package gg.guab.items;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
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
        player.playSound(Sound.sound(key, Sound.Source.PLAYER, volume, pitch));
    }
}
