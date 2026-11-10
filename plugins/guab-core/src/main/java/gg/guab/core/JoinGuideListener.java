package gg.guab.core;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Join welcome guide. Registered independently of the HUD so admins can turn the
 * sidebar off and still keep the "where do I go" message.
 */
final class JoinGuideListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Key HUD_FONT = Key.key("guab", "hud");
    private static final String GLYPH_COIN = "\uE000";

    private static final List<String> DEFAULT_FIRST_JOIN = List.of(
            "&6&lWelcome to Guab, &f%player%&6!",
            "&7You start with &6%points% &7points.",
            "&eMenu &8» &f/guab",
            "&ePub &8» &f/beer &7· &ePit &8» &f/battlepit join",
            "&eCasino &8» &f/casino &7· &eDarts &8» &f/darts");

    private static final List<String> DEFAULT_RETURNING = List.of(
            "&6&lWelcome back, &f%player%&6!",
            "&7Balance &8» &6%points% &7points",
            "&eMenu &8» &f/guab");

    private final GuabCorePlugin plugin;

    JoinGuideListener(GuabCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> send(event.getPlayer()));
    }

    private void send(Player player) {
        if (!player.isOnline()
                || !plugin.getConfig().getBoolean("hud.join-message.enabled", true)) {
            return;
        }
        boolean firstJoin = !player.hasPlayedBefore();
        String path = firstJoin
                ? "hud.join-message.first-join.lines"
                : "hud.join-message.returning.lines";
        List<String> template = plugin.getConfig().getStringList(path);
        if (template.isEmpty() && !firstJoin) {
            template = plugin.getConfig().getStringList("hud.join-message.lines");
        }
        if (template.isEmpty()) {
            template = firstJoin ? DEFAULT_FIRST_JOIN : DEFAULT_RETURNING;
        }
        player.sendMessage(Component.empty());
        for (String line : template) {
            Component message = LEGACY.deserialize(replace(line, player));
            if (line.contains("%points%")) {
                message = withIcon(message);
            }
            player.sendMessage(message);
        }
        player.sendMessage(Component.empty()
                .append(button("[Open menu]", "/guab", NamedTextColor.GREEN,
                        "Open the Guab menu"))
                .append(Component.text("  "))
                .append(button("[Settings]", "/settings", NamedTextColor.AQUA,
                        "Choose HUD, sound and title preferences")));
    }

    private String replace(String raw, Player player) {
        UUID id = player.getUniqueId();
        return raw
                .replace("%player%", player.getName())
                .replace("%points%", String.format(Locale.ROOT, "%,d",
                        plugin.points().get(id)))
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()));
    }

    private Component withIcon(Component body) {
        if (!plugin.getConfig().getBoolean("hud.font-icons", true)) {
            return body;
        }
        return Component.empty()
                .append(Component.text(GLYPH_COIN, NamedTextColor.GOLD).font(HUD_FONT))
                .append(Component.text(" "))
                .append(body);
    }

    private Component button(String label, String command, NamedTextColor colour, String hover) {
        return Component.text(label, colour)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }
}
