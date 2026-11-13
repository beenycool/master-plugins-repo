package gg.guab.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Locale;

/**
 * Shared {@code [Plugin]} chat formatting. Every plugin used to carry its own
 * slightly different {@code info()}/{@code error()} helper; routing them through
 * here keeps colours and prefixes consistent across the server.
 */
public final class GuabMessages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private GuabMessages() {
    }

    public static Component prefix(String pluginName, NamedTextColor colour) {
        return Component.text("[" + pluginName + "] ", colour);
    }

    public static Component info(String pluginName, String message) {
        return info(pluginName, NamedTextColor.GOLD, message);
    }

    public static Component info(String pluginName, NamedTextColor colour, String message) {
        return prefix(pluginName, colour)
                .append(Component.text(message == null ? "" : message, NamedTextColor.WHITE));
    }

    public static Component error(String pluginName, String message) {
        return prefix(pluginName, NamedTextColor.RED)
                .append(Component.text(message == null ? "" : message, NamedTextColor.GRAY));
    }

    /** Formats a cooldown as seconds, keeping a useful decimal below one second. */
    public static String seconds(long millis) {
        if (millis >= 1000L) {
            return String.format(Locale.ROOT, "%.0f", millis / 1000.0);
        }
        return String.format(Locale.ROOT, "%.1f", Math.max(0L, millis) / 1000.0);
    }

    /** Parses legacy ampersand text through the shared serializer. */
    public static Component legacy(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }
}
