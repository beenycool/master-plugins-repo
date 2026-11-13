package gg.guab.core;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Bitmap glyphs from the {@code guab:hud} font shipped in {@code guab-items.zip}
 * (and embedded in the rickroll pack). The codepoints match the glyph order in
 * {@code tools/build-guab-hud-assets.py}.
 *
 * <p>Private-use codepoints render as missing-character boxes for clients that
 * do not have the font, so callers must go through {@link #withIcon}. It keeps
 * the original text when {@code hud.font-icons} is disabled or when the player
 * declined the resource pack.</p>
 */
public final class GuabGlyphs {

    public static final char COIN = '\uE000';
    public static final char BEER = '\uE001';
    public static final char DART = '\uE002';
    public static final char HEART = '\uE003';
    public static final char SKULL = '\uE004';
    public static final char LOGO = '\uE005';

    public static final Key HUD_FONT = Key.key("guab", "hud");

    private GuabGlyphs() {
    }

    /**
     * The glyph itself, carrying the custom font. The font is applied only to
     * this component, so appended text keeps the default font.
     */
    public static Component icon(char glyph, NamedTextColor colour) {
        NamedTextColor safeColour = colour == null ? NamedTextColor.WHITE : colour;
        return Component.text(String.valueOf(glyph), safeColour).font(HUD_FONT);
    }

    /** Config-only variant for callers that have no player (for example static text). */
    public static Component withIcon(boolean fontIcons, char glyph, NamedTextColor colour,
                                     Component body) {
        return withIcon(fontIcons, false, glyph, colour, body);
    }

    /** Variant for callers that already resolved the player's pack state. */
    public static Component withIcon(boolean fontIcons, boolean declined, char glyph,
                                     NamedTextColor colour, Component body) {
        if (!fontIcons || declined) {
            return body;
        }
        return Component.empty()
                .append(icon(glyph, colour))
                .append(Component.text(" "))
                .append(body);
    }

    /**
     * Player-aware variant: shows the icon only when the player has not declined
     * the resource pack. Safe to call before the service is registered.
     */
    public static Component withIcon(boolean fontIcons, Player player, char glyph,
                                     NamedTextColor colour, Component body) {
        PackStatusService service = service(PackStatusService.class);
        boolean configIcons = service == null || service.fontIconsEnabled();
        return withIcon(fontIcons && configIcons, declined(player), glyph, colour, body);
    }

    /** Whether the player is known to have declined the server resource pack. */
    public static boolean declined(Player player) {
        if (player == null) {
            return false;
        }
        PackStatusService service = service(PackStatusService.class);
        return service != null && service.declined(player.getUniqueId());
    }

    private static <T> T service(Class<T> type) {
        var registration = Bukkit.getServicesManager().getRegistration(type);
        return registration == null ? null : registration.getProvider();
    }
}
