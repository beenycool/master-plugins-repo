package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * {@code /guab} and {@code /menu}: the entry point for the native hub.
 */
public final class GuabMenuCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "guab.menu.use";
    private static final List<String> PAGES =
            List.of("pub", "casino", "darts", "quests", "stats");

    private final GuabItemsPlugin plugin;

    public GuabMenuCommand(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[Guab] ", NamedTextColor.GOLD)
                    .append(Component.text("Only players can open the hub.", NamedTextColor.RED)));
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Component.text("[Guab] ", NamedTextColor.GOLD)
                    .append(Component.text("You need " + PERMISSION + " to open the hub.",
                            NamedTextColor.RED)));
            return true;
        }
        if (args.length == 0) {
            plugin.menu().openHome(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "home", "menu" -> plugin.menu().openHome(player);
            case "pub", "beer" -> plugin.menu().openPub(player);
            case "casino" -> plugin.menu().openCasino(player);
            case "darts", "dart" -> plugin.menu().openDarts(player);
            case "quests", "quest" -> plugin.menu().openQuests(player);
            case "stats", "stat" -> plugin.menu().openStats(player);
            default -> {
                player.sendMessage(Component.text("[Guab] ", NamedTextColor.GOLD)
                        .append(Component.text("Usage: /" + label
                                + " [pub|casino|darts|quests|stats]", NamedTextColor.GRAY)));
                return true;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION) || args.length != 1) {
            return List.of();
        }
        String lower = args[0].toLowerCase(Locale.ROOT);
        return PAGES.stream()
                .filter(page -> page.startsWith(lower))
                .toList();
    }
}
