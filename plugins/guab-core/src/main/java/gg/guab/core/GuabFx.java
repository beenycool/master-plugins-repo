package gg.guab.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * Shared transition helpers so a teleport between venues reads as a scene change
 * rather than a coordinate jump.
 */
public final class GuabFx {

    private GuabFx() {
    }

    public static void arrival(Player player, String title, NamedTextColor colour, Sound sound) {
        arrival(player, title, colour, sound, 1.0f);
    }

    public static void arrival(Player player, String title, NamedTextColor colour, Sound sound,
                               float pitch) {
        PlayerPreferencesStore preferences = service(PlayerPreferencesStore.class);
        boolean showTitle = preferences == null || preferences.titles(player.getUniqueId());
        boolean playSound = preferences == null || preferences.sounds(player.getUniqueId());
        arrival(player, title, colour, sound, pitch, showTitle, playSound);
    }

    /**
     * Arrival effects with independent title and sound switches, so a player's
     * preference can turn one off without losing the other.
     */
    public static void arrival(Player player, String title, NamedTextColor colour, Sound sound,
                               float pitch, boolean showTitle, boolean playSound) {
        if (showTitle) {
            player.showTitle(Title.title(
                    Component.text(title, colour),
                    Component.empty(),
                    Title.Times.times(
                            Duration.ofMillis(200),
                            Duration.ofMillis(1200),
                            Duration.ofMillis(400))));
        }
        if (playSound) {
            player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, 0.8f, pitch);
        }
    }

    public static void toast(Player player, String message, NamedTextColor colour, Sound sound) {
        Component component = Component.text(message, colour);
        HudChannel channel = service(HudChannel.class);
        if (channel != null) {
            channel.send(player, component, HudChannel.PRIORITY_HINT, 40);
        } else {
            player.sendActionBar(component);
        }
        PlayerPreferencesStore preferences = service(PlayerPreferencesStore.class);
        if (preferences == null || preferences.sounds(player.getUniqueId())) {
            player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, 0.5f, 1.0f);
        }
    }

    private static <T> T service(Class<T> type) {
        var registration = Bukkit.getServicesManager().getRegistration(type);
        return registration == null ? null : registration.getProvider();
    }
}
