package gg.guab.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Shows the shared deaths/kills/rickrolls/parkour tracker. */
public final class StatsCommand implements CommandExecutor, TabCompleter {

    private final GuabCorePlugin plugin;
    private final PlayerResolver resolver;

    public StatsCommand(GuabCorePlugin plugin) {
        this.plugin = plugin;
        this.resolver = new PlayerResolver(plugin.points(), plugin.stats());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("top")) {
            if (!sender.hasPermission("guab.stats.others")) {
                error(sender, "You need guab.stats.others to view leaderboards.");
                return true;
            }
            return top(sender, args);
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                error(sender, "Run this in game, or use /stats <player>.");
                return true;
            }
            if (!player.hasPermission("guab.stats.use")) {
                error(sender, "You need guab.stats.use to view your stats.");
                return true;
            }
            show(sender, player.getUniqueId(), player.getName());
            return true;
        }

        if (!sender.hasPermission("guab.stats.others")) {
            error(sender, "You need guab.stats.others to check another player.");
            return true;
        }
        PlayerResolver.Match target = resolver.resolve(args[0]);
        if (target == null) {
            error(sender, "Unknown player: " + args[0]);
            return true;
        }
        show(sender, target.id(), target.displayName());
        return true;
    }

    private void show(CommandSender sender, UUID id, String fallbackName) {
        String name = plugin.stats().name(id);
        if (name == null || name.equals("unknown")) {
            name = fallbackName;
        }
        sender.sendMessage(Component.text("[" + name + "'s stats]", NamedTextColor.GOLD));
        sender.sendMessage(line("Deaths", plugin.stats().get(id, StatsService.DEATHS), NamedTextColor.RED));
        sender.sendMessage(line("Void deaths", plugin.stats().get(id, StatsService.VOID_DEATHS), NamedTextColor.DARK_RED));
        sender.sendMessage(line("Kills", plugin.stats().get(id, StatsService.KILLS), NamedTextColor.GREEN));
        sender.sendMessage(line("Rickrolls", plugin.stats().get(id, StatsService.RICKROLLS), NamedTextColor.LIGHT_PURPLE));
        sender.sendMessage(line("Parkour", plugin.stats().get(id, StatsService.PARKOUR), NamedTextColor.AQUA));
    }

    private boolean top(CommandSender sender, String[] args) {
        String metric = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : StatsService.DEATHS;
        if (!StatsService.METRICS.contains(metric)) {
            error(sender, "Metrics: " + String.join(", ", StatsService.METRICS));
            return true;
        }
        int limit = 5;
        if (args.length >= 3) {
            try {
                limit = Math.clamp(Integer.parseInt(args[2]), 1, 20);
            } catch (NumberFormatException exception) {
                error(sender, "Usage: /stats top <metric> [count]");
                return true;
            }
        }
        List<StatsService.Entry> ranking = plugin.stats().top(metric, limit);
        if (ranking.isEmpty()) {
            info(sender, "No " + metric + " tracked yet.");
            return true;
        }
        info(sender, "Top " + metric + ":");
        int rank = 1;
        for (StatsService.Entry entry : ranking) {
            sender.sendMessage(Component.text("  #" + rank + " ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(entry.name(), NamedTextColor.WHITE))
                    .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(String.format(Locale.ROOT, "%,d", entry.value()),
                            NamedTextColor.GOLD)));
            rank++;
        }
        return true;
    }

    private Component line(String label, int value, NamedTextColor colour) {
        return Component.text("  " + label + ": ", NamedTextColor.GRAY)
                .append(Component.text(String.format(Locale.ROOT, "%,d", value), colour));
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Stats] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Stats] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.RED)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("guab.stats.others")) {
                options.add("top");
                for (Player player : Bukkit.getOnlinePlayers()) {
                    options.add(player.getName());
                }
            }
            return options.stream()
                    .filter(option -> option.toLowerCase(Locale.ROOT)
                            .startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            return StatsService.METRICS.stream()
                    .filter(metric -> metric.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("top")) {
            return List.of("5", "10", "20", "50").stream()
                    .filter(value -> value.startsWith(args[2]))
                    .toList();
        }
        return List.of();
    }
}
