package gg.guab.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PointsCommand implements CommandExecutor, TabCompleter {

    private final GuabCorePlugin plugin;

    public PointsCommand(GuabCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                error(sender, "Run this in game, or use /points <player>.");
                return true;
            }
            sendBalance(sender, player.getName(), plugin.points().get(player.getUniqueId()));
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("top") || first.equals("leaderboard")) {
            return top(sender, args);
        }
        if (first.equals("set") || first.equals("add") || first.equals("take")) {
            return mutate(sender, first, args);
        }
        if (!sender.hasPermission("guab.points.others")) {
            error(sender, "You need guab.points.others to check another player.");
            return true;
        }
        OfflinePlayer target = resolve(args[0]);
        if (target == null) {
            error(sender, "Unknown player: " + args[0]);
            return true;
        }
        sendBalance(sender, target.getName() == null ? args[0] : target.getName(), plugin.points().get(target.getUniqueId()));
        return true;
    }

    private boolean mutate(CommandSender sender, String action, String[] args) {
        if (!sender.hasPermission("guab.points.admin")) {
            error(sender, "You need guab.points.admin to change balances.");
            return true;
        }
        if (args.length != 3) {
            error(sender, "Usage: /points " + action + " <player> <amount>");
            return true;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            error(sender, "Unknown player: " + args[1]);
            return true;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            error(sender, "Amount must be a whole number.");
            return true;
        }
        if (amount < 0) {
            error(sender, "Amount must be zero or more.");
            return true;
        }

        int balance;
        switch (action) {
            case "set" -> {
                plugin.points().set(target.getUniqueId(), amount);
                balance = plugin.points().get(target.getUniqueId());
            }
            case "add" -> balance = plugin.points().add(target.getUniqueId(), amount);
            case "take" -> {
                if (!plugin.points().take(target.getUniqueId(), amount)) {
                    error(sender, "That player does not have " + amount + " points.");
                    return true;
                }
                balance = plugin.points().get(target.getUniqueId());
            }
            default -> {
                return true;
            }
        }
        String name = target.getName() == null ? args[1] : target.getName();
        info(sender, name + " now has " + balance + " points.");
        return true;
    }

    private boolean top(CommandSender sender, String[] args) {
        int limit = 5;
        if (args.length >= 2) {
            try {
                limit = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                error(sender, "Usage: /points top [count]");
                return true;
            }
        }
        limit = Math.max(1, Math.min(20, limit));
        List<Map.Entry<UUID, Integer>> ranking = plugin.points().top(limit);
        if (ranking.isEmpty()) {
            info(sender, "No points have been earned yet.");
            return true;
        }
        info(sender, "Top " + ranking.size() + " balances:");
        int rank = 1;
        for (Map.Entry<UUID, Integer> entry : ranking) {
            String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
            if (name == null) {
                name = entry.getKey().toString().substring(0, 8);
            }
            sender.sendMessage(Component.text("  #" + rank + " ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(String.format(Locale.ROOT, "%,d", entry.getValue()), NamedTextColor.GOLD)));
            rank++;
        }
        return true;
    }

    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline;
    }

    private void sendBalance(CommandSender sender, String name, int balance) {
        info(sender, name + " has " + balance + " points.");
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Points] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Points] ", NamedTextColor.RED)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("top");
            if (sender.hasPermission("guab.points.admin")) {
                out.addAll(List.of("set", "add", "take"));
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                out.add(player.getName());
            }
        } else if (args.length == 2 && sender.hasPermission("guab.points.admin")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                out.add(player.getName());
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
