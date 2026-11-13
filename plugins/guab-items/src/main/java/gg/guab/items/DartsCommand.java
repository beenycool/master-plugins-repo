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
    private static final List<String> MODES = List.of("101", "301", "501");

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
            case "start", "play" -> start(sender, args);
            case "again", "rematch", "replay" -> again(sender);
            case "practice", "practise", "training" -> practice(sender, args);
            case "challenge", "1v1" -> challenge(sender, args);
            case "accept" -> accept(sender, args);
            case "quit", "stop", "leave" -> quit(sender);
            case "status", "info" -> status(sender);
            case "stats", "record" -> stats(sender, args);
            case "top", "leaderboard" -> top(sender, args);
            case "help", "howto" -> help(sender);
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
            mode = parseMode(sender, args[1]);
            if (mode == 0) {
                return;
            }
        }
        if (args.length >= 3) {
            try {
                legs = Integer.parseInt(args[2]);
            } catch (NumberFormatException exception) {
                error(sender, "Legs must be a number from 1 to 9.");
                return;
            }
            if (legs < 1 || legs > 9) {
                error(sender, "Legs must be a number from 1 to 9.");
                return;
            }
        }
        plugin.darts().startGame(player, mode, legs);
    }

    private void again(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can replay a leg.");
            return;
        }
        DartsManager.Preset preset = plugin.darts().lastPreset(player.getUniqueId());
        if (preset == null) {
            error(sender, "You have not played yet. Try /darts start.");
            return;
        }
        plugin.darts().startGame(player, preset.mode(), preset.legs());
    }

    private void practice(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can practise.");
            return;
        }
        int mode = plugin.darts().config().defaultMode();
        if (args.length >= 2) {
            mode = parseMode(sender, args[1]);
            if (mode == 0) {
                return;
            }
        }
        plugin.darts().startGame(player, mode, 1, true);
    }

    private int parseMode(CommandSender sender, String raw) {
        try {
            int mode = Integer.parseInt(raw);
            if (mode == 101 || mode == 301 || mode == 501) {
                return mode;
            }
        } catch (NumberFormatException ignored) {
            // Fall through to the shared message.
        }
        error(sender, "Mode must be 101, 301 or 501.");
        return 0;
    }

    private void challenge(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can challenge someone.");
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /darts challenge <player> [bet]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            error(sender, "Player not online: " + args[1]);
            return;
        }
        long stake = 0L;
        if (args.length >= 3) {
            try {
                stake = Long.parseLong(args[2]);
            } catch (NumberFormatException exception) {
                error(sender, "The bet must be a whole number of points.");
                return;
            }
            if (stake <= 0L) {
                error(sender, "The bet must be at least 1 point.");
                return;
            }
            long max = plugin.darts().config().maxStake();
            if (max <= 0L) {
                error(sender, "Wagered 1v1s are disabled.");
                return;
            }
            if (stake > max) {
                error(sender, "The maximum bet is " + max + " points.");
                return;
            }
        }
        plugin.darts().challenge(player, target, stake);
    }

    private void accept(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can accept a challenge.");
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /darts accept <player>");
            return;
        }
        Player challenger = Bukkit.getPlayerExact(args[1]);
        if (challenger == null) {
            error(sender, "Player not online: " + args[1]);
            return;
        }
        plugin.darts().accept(player, challenger);
    }

    private void quit(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can leave a leg.");
            return;
        }
        if (!plugin.darts().isPlaying(player.getUniqueId())) {
            info(sender, "You are not in a leg. Start one with /darts start.");
            return;
        }
        plugin.darts().endGame(player, "You step away from the oche.");
    }

    private void status(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Use /darts status in game.");
            return;
        }
        sender.sendMessage(prefix().append(Component.text("Your darts status", NamedTextColor.GOLD)));
        for (String line : plugin.darts().statusLines(player)) {
            sender.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
        }
    }

    private void stats(CommandSender sender, String[] args) {
        DartsStatsStore.Stats stats;
        String name;
        if (args.length >= 2) {
            // Public records: no admin permission and offline players are read
            // straight from darts.yml.
            name = args[1];
            stats = plugin.darts().statsByName(name);
            if (stats == null) {
                Player online = Bukkit.getPlayerExact(name);
                if (online != null) {
                    stats = plugin.darts().stats(online.getUniqueId());
                    name = online.getName();
                }
            }
            if (stats == null) {
                error(sender, "No darts record for " + args[1] + ".");
                return;
            }
        } else if (sender instanceof Player player) {
            stats = plugin.darts().stats(player.getUniqueId());
            name = player.getName();
        } else {
            error(sender, "Use /darts stats <player> from the console.");
            return;
        }

        sender.sendMessage(prefix().append(Component.text(name + "'s darts record",
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
        sender.sendMessage(prefix().append(Component.text("Darts — top " + metric,
                NamedTextColor.GOLD)));
        for (int i = 0; i < entries.size(); i++) {
            sender.sendMessage(Component.text("  " + (i + 1) + ". ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(entries.get(i).getKey(), NamedTextColor.WHITE))
                    .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(entries.get(i).getValue(), NamedTextColor.GOLD)));
        }
    }

    private void help(CommandSender sender) {
        DartsConfig config = plugin.darts().config();
        boolean beginner = config.beginnerAssist();
        sender.sendMessage(prefix().append(Component.text("How to play darts", NamedTextColor.GOLD)));
        sender.sendMessage(Component.text("  1. Right-click the dartboard on the pub's west wall.",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  2. Pick 101, 301 or 501 (default " + config.defaultMode()
                + "). First to " + config.legsToWin() + " leg(s). You'll be teleported to the darts room.",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  3. Hold right-click to draw the bow, release to shoot the target.",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  4. Three arrows per visit. " + (beginner
                ? "Beginner assist is on: no busts, and any dart can finish on exactly 0."
                : "Double out: the last arrow must be a double (bullseye 50 counts)."),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  5. Challenge someone with /darts challenge <player> [bet]; they accept with "
                + "/darts accept <you>. A bet can be up to " + config.maxStake() + " points.",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  Practice with /darts practice [101|301|501] (no stats or rewards), "
                + "or replay with /darts again.", NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.text("  Checkout hints show on the action bar. Use /darts status, "
                + "/darts stats [player], /darts top or /darts quit.", NamedTextColor.DARK_GRAY));
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
        sender.sendMessage(Component.text("/" + label + " again | practice [101|301|501] | status", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " challenge <player> [bet] | accept <player>",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " quit | stats [player] | top [legs|180s|checkout|best] | help",
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
            List<String> options = new ArrayList<>(List.of(
                    "menu", "start", "play", "again", "rematch", "replay", "practice", "practise",
                    "training", "challenge", "1v1", "accept", "quit", "stop", "leave", "status",
                    "info", "stats", "record", "top", "leaderboard", "help", "howto"));
            if (sender.hasPermission(ADMIN)) {
                options.add("board");
                options.add("setboard");
                options.add("reload");
            }
            return matching(options, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("start") || sub.equals("play") || sub.equals("practice")
                    || sub.equals("practise") || sub.equals("training")) {
                return matching(MODES, args[1]);
            }
            if (sub.equals("top") || sub.equals("leaderboard")) {
                return matching(List.of("legs", "180s", "checkout", "best", "darts", "thrown"), args[1]);
            }
            if (sub.equals("stats") || sub.equals("record")
                    || sub.equals("challenge") || sub.equals("1v1")
                    || sub.equals("accept")) {
                return matching(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            }
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("start")
                || args[0].equalsIgnoreCase("play"))) {
            return matching(List.of("1", "3", "5", "7", "9"), args[2]);
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("challenge")
                || args[0].equalsIgnoreCase("1v1"))) {
            long max = plugin.darts().config().maxStake();
            return max > 0L ? matching(List.of(String.valueOf(max)), args[2]) : List.of();
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
