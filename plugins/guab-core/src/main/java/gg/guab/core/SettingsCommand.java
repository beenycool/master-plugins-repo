package gg.guab.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared implementation for {@code /hud} and {@code /settings}. Every toggle is
 * persisted by {@link PlayerPreferencesStore}; {@code /hud} with no arguments keeps
 * its old behaviour of flipping the sidebar.
 */
public final class SettingsCommand implements CommandExecutor, TabCompleter {

    private static final int TOGGLE = 0;
    private static final int ON = 1;
    private static final int OFF = 2;
    private static final int INVALID = 3;

    private static final List<String> FEATURES = List.of(
            "sidebar", "tab", "nametag", "sounds", "actionbar", "titles",
            "rickroll-motion", "rickroll");

    private final PlayerPreferencesStore preferences;
    private final HudManager hud;

    public SettingsCommand(PlayerPreferencesStore preferences, HudManager hud) {
        this.preferences = preferences;
        this.hud = hud;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Settings are per-player; run this in game.");
            return true;
        }
        if (args.length == 0) {
            if (command.getName().equalsIgnoreCase("hud")) {
                apply(player, PlayerPreferencesStore.Toggle.SIDEBAR, TOGGLE);
            } else {
                summary(player);
            }
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("summary") || first.equals("menu") || first.equals("list")) {
            summary(player);
            return true;
        }

        PlayerPreferencesStore.Toggle toggle = PlayerPreferencesStore.Toggle.parse(first);
        if (toggle == null) {
            int mode = parseMode(first);
            if (mode != INVALID) {
                apply(player, PlayerPreferencesStore.Toggle.SIDEBAR, mode);
            } else {
                error(sender, "Usage: /hud [sidebar|tab|nametag|sounds|actionbar|titles"
                        + "|rickroll-motion|rickroll] [on|off|toggle]");
            }
            return true;
        }

        int mode = args.length >= 2 ? parseMode(args[1]) : TOGGLE;
        if (mode == INVALID) {
            error(sender, "Value must be on, off or toggle.");
            return true;
        }
        apply(player, toggle, mode);
        return true;
    }

    private void apply(Player player, PlayerPreferencesStore.Toggle toggle, int mode) {
        boolean value;
        if (mode == TOGGLE) {
            value = preferences.toggle(player.getUniqueId(), toggle);
        } else {
            value = mode == ON;
            preferences.set(player.getUniqueId(), toggle, value);
        }
        if (hud != null) {
            hud.applyPreferences(player);
        }
        String state = value ? "on" : "off";
        player.sendMessage(Component.text("[Settings] ", NamedTextColor.GOLD)
                .append(Component.text(label(toggle) + ": ", NamedTextColor.GRAY))
                .append(Component.text(state, value ? NamedTextColor.GREEN : NamedTextColor.RED)));
        if (hud == null && (toggle == PlayerPreferencesStore.Toggle.SIDEBAR
                || toggle == PlayerPreferencesStore.Toggle.TAB
                || toggle == PlayerPreferencesStore.Toggle.NAMETAG)) {
            player.sendMessage(Component.text(
                    "The HUD is disabled on this server; your preference has been saved.",
                    NamedTextColor.DARK_GRAY));
        }
        if (toggle == PlayerPreferencesStore.Toggle.SIDEBAR && !value) {
            player.sendMessage(Component.text(
                    "Your balance stays under your nametag.", NamedTextColor.DARK_GRAY));
        }
    }

    private void summary(Player player) {
        PlayerPreferencesStore.Preferences prefs = preferences.snapshot(player.getUniqueId());
        player.sendMessage(Component.text("[Settings]", NamedTextColor.GOLD)
                .append(Component.text(" Click a button to toggle it.", NamedTextColor.GRAY)));
        player.sendMessage(toggleButton("Sidebar", "sidebar", prefs.get(
                PlayerPreferencesStore.Toggle.SIDEBAR)));
        player.sendMessage(toggleButton("Tab list", "tab", prefs.get(
                PlayerPreferencesStore.Toggle.TAB)));
        player.sendMessage(toggleButton("Nametag balance", "nametag", prefs.get(
                PlayerPreferencesStore.Toggle.NAMETAG)));
        player.sendMessage(toggleButton("Point sounds", "sounds", prefs.get(
                PlayerPreferencesStore.Toggle.SOUNDS)));
        player.sendMessage(toggleButton("Action-bar feedback", "actionbar", prefs.get(
                PlayerPreferencesStore.Toggle.ACTIONBAR)));
        player.sendMessage(toggleButton("Arrival titles", "titles", prefs.get(
                PlayerPreferencesStore.Toggle.TITLES)));
        player.sendMessage(toggleButton("Rickroll reduced motion", "rickroll-motion",
                prefs.get(PlayerPreferencesStore.Toggle.RICKROLL_REDUCED_MOTION)));
        player.sendMessage(toggleButton("Rickroll opt-out", "rickroll",
                prefs.get(PlayerPreferencesStore.Toggle.RICKROLL_OPT_OUT)));
        player.sendMessage(Component.text("[Open menu]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/guab"))
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Open the Guab menu", NamedTextColor.GRAY))));
    }

    private Component toggleButton(String label, String feature, boolean enabled) {
        String state = enabled ? "ON" : "OFF";
        return Component.text("  " + label + ": ", NamedTextColor.GRAY)
                .append(Component.text("[" + state + "]", enabled
                                ? NamedTextColor.GREEN : NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/hud " + feature + " toggle"))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Click to turn " + label.toLowerCase(Locale.ROOT)
                                        + (enabled ? " off" : " on"),
                                NamedTextColor.GRAY))));
    }

    private static String label(PlayerPreferencesStore.Toggle toggle) {
        return switch (toggle) {
            case SIDEBAR -> "Sidebar";
            case TAB -> "Tab list";
            case NAMETAG -> "Nametag balance";
            case SOUNDS -> "Point sounds";
            case ACTIONBAR -> "Action-bar feedback";
            case TITLES -> "Arrival titles";
            case RICKROLL_REDUCED_MOTION -> "Rickroll reduced motion";
            case RICKROLL_OPT_OUT -> "Rickroll opt-out";
        };
    }

    private static int parseMode(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable", "enabled", "show" -> ON;
            case "off", "false", "disable", "disabled", "hide" -> OFF;
            case "toggle", "flip" -> TOGGLE;
            default -> INVALID;
        };
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Settings] ", NamedTextColor.RED)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(FEATURES);
            out.addAll(List.of("on", "off", "toggle", "summary"));
        } else if (args.length == 2) {
            PlayerPreferencesStore.Toggle toggle = PlayerPreferencesStore.Toggle.parse(args[0]);
            if (toggle != null) {
                out.addAll(List.of("on", "off", "toggle"));
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
