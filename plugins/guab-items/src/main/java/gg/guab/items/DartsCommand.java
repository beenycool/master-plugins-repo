package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DartsCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "guab.pub.darts.admin";

    private final GuabItemsPlugin plugin;

    public DartsCommand(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (sender instanceof Player player) {
                plugin.darts().openMenu(player);
            } else {
                usage(sender, label);
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> start(sender, args);
            case "quit", "stop", "leave" -> quit(sender);
            case "stats", "record" -> stats(sender, args);
            case "top", "leaderboard" -> top(sender, args);
            case "board", "setboard" -> board(sender);
            case "reload" -> reload(sender);
            default -> usage(sender, label);
        }
        return true;
    }

    private void start(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can throw darts.");
            return;
        }
        int mode = plugin.darts().config().defaultMode();
        int legs = plugin.darts().config().legsToWin();
        if (args.length >= 2) {
            try {
                mode = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                error(sender, "Mode must be 101, 301 or 501.");
                return;
            }
        }
        if (args.length >= 3) {
            try {
                legs = Math.clamp(Integer.parseInt(args[2]), 1, 9);
            } catch (NumberFormatException exception) {
                error(sender, "Legs must be a number from 1 to 9.");
                return;
            }
        }
        plugin.darts().startGame(player, mode, legs);
    }

    private void quit(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can leave a leg.");
            return;
        }
        plugin.darts().endGame(player, "You step away from the oche.");
    }

    private void stats(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission(ADMIN)) {
                error(sender, "You need " + ADMIN + " to inspect another player.");
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                error(sender, "Player not online: " + args[1]);
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            error(sender, "Use /darts stats <player> from the console.");
            return;
        }

        DartsStatsStore.Stats stats = plugin.darts().stats(target.getUniqueId());
        sender.sendMessage(prefix().append(Component.text(target.getName() + "'s darts record",
                NamedTextColor.GOLD)));
        line(sender, "Legs", stats.legsWon() + " won / " + stats.legsPlayed() + " played");
        line(sender, "Darts", stats.dartsThrown() + " thrown");
        line(sender, "Best leg", stats.bestLegDarts() == 0 ? "—" : stats.bestLegDarts() + " darts");
        line(sender, "180s", String.valueOf(stats.oneEighties()));
        line(sender, "Checkouts", stats.checkouts() + " (highest " + stats.highestCheckout() + ")");
        line(sender, "Best visit", String.valueOf(stats.bestVisit()));
    }

    private void top(CommandSender sender, String[] args) {
        String metric = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "legs";
        List<Map.Entry<String, Integer>> entries = plugin.darts().top(metric, 10);
        if (entries.isEmpty()) {
            info(sender, "No darts records yet. The board is waiting.");
            return;
        }
        sender.sendMessage(prefix().append(Component.text("Darts — top " + metric, NamedTextColor.GOLD)));
        for (int i = 0; i < entries.size(); i++) {
            sender.sendMessage(Component.text("  " + (i + 1) + ". ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(entries.get(i).getKey(), NamedTextColor.WHITE))
                    .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(entries.get(i).getValue(), NamedTextColor.GOLD)));
        }
    }

    private void board(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            error(sender, "You need " + ADMIN + " to move the board.");
            return;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can target a block for the board.");
            return;
        }
        Block block = player.getTargetBlockExact(8);
        BlockFace face = player.getTargetBlockFace(8);
        if (block == null || face == null) {
            error(sender, "Look at the block that should be the dartboard.");
            return;
        }
        if (face != BlockFace.NORTH && face != BlockFace.EAST
                && face != BlockFace.SOUTH && face != BlockFace.WEST) {
            error(sender, "The board must be on a north, east, south or west face.");
            return;
        }
        plugin.darts().setBoard(block, face);
        info(sender, "Dartboard set to " + block.getWorld().getName() + " "
                + block.getX() + " " + block.getY() + " " + block.getZ()
                + " facing " + face.name() + ".");
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            error(sender, "You need " + ADMIN + " to reload the board.");
            return;
        }
        plugin.reload();
        info(sender, "Darts and pub configuration reloaded.");
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("/" + label + " start [101|301|501] [legs]", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " quit | stats [player] | top [legs|180s|checkout|best]",
                NamedTextColor.GRAY));
        if (sender.hasPermission(ADMIN)) {
            sender.sendMessage(Component.text("/" + label + " board | reload", NamedTextColor.DARK_GRAY));
        }
    }

    private void line(CommandSender sender, String key, String value) {
        sender.sendMessage(Component.text("  " + key + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE)));
    }

    private Component prefix() {
        return Component.text("[Darts] ", NamedTextColor.GOLD);
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.GRAY)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("guab.pub.darts.use")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("start", "quit", "stats", "top", "menu"));
            if (sender.hasPermission(ADMIN)) {
                options.add("board");
                options.add("reload");
            }
            return matching(options, args[0]);
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("start")) {
                return matching(List.of("101", "301", "501"), args[1]);
            }
            if (args[0].equalsIgnoreCase("top")) {
                return matching(List.of("legs", "180s", "checkout", "best", "darts"), args[1]);
            }
            if (args[0].equalsIgnoreCase("stats") && sender.hasPermission(ADMIN)) {
                return matching(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("start")) {
            return matching(List.of("1", "3", "5"), args[2]);
        }
        return List.of();
    }

    private List<String> matching(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
