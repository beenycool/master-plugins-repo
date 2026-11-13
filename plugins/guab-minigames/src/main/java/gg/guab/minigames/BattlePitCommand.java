package gg.guab.minigames;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class BattlePitCommand implements CommandExecutor, TabCompleter {

    private static final List<String> METRICS = List.of("kills", "deaths", "streak");

    private final GuabMinigamesPlugin plugin;
    private final BattlePit pit;

    public BattlePitCommand(GuabMinigamesPlugin plugin, BattlePit pit) {
        this.plugin = plugin;
        this.pit = pit;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join" -> join(sender);
            case "leave", "quit" -> leave(sender);
            case "setspawn" -> setSpawn(sender, args);
            case "setexit" -> setExit(sender);
            case "status" -> status(sender);
            case "stats" -> stats(sender, args);
            case "top" -> top(sender, args);
            case "bounty" -> bounty(sender, args);
            case "kick" -> kick(sender, args);
            case "purge" -> purge(sender);
            case "reload" -> reload(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void join(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can join the battle pit.");
            return;
        }
        pit.join(player);
    }

    private void leave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can leave the battle pit.");
            return;
        }
        pit.leave(player);
    }

    private void setSpawn(CommandSender sender, String[] args) {
        if (!sender.hasPermission("guab.battlepit.admin")) {
            error(sender, "You need guab.battlepit.admin to set the pit spawn.");
            return;
        }
        Location location = resolveLocation(sender, args);
        if (location == null) {
            return;
        }
        pit.setSpawn(location);
        info(sender, "Battle pit spawn set to " + pit.formatLocation(location) + ".");
    }

    private void setExit(CommandSender sender) {
        if (!sender.hasPermission("guab.battlepit.admin")) {
            error(sender, "You need guab.battlepit.admin to set the pit exit.");
            return;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Run this in game, or set battlepit.exit in the config.");
            return;
        }
        pit.setExit(player.getLocation());
        info(sender, "Battle pit exit set to " + pit.formatLocation(player.getLocation()) + ".");
    }

    private Location resolveLocation(CommandSender sender, String[] args) {
        if (args.length >= 4) {
            World world = args.length >= 5 ? Bukkit.getWorld(args[4]) : Bukkit.getWorlds().getFirst();
            if (world == null) {
                error(sender, "Unknown world: " + args[4]);
                return null;
            }
            try {
                return new Location(world,
                        Double.parseDouble(args[1]),
                        Double.parseDouble(args[2]),
                        Double.parseDouble(args[3]));
            } catch (NumberFormatException exception) {
                error(sender, "Usage: /battlepit setspawn <x> <y> <z> [world]");
                return null;
            }
        }
        if (sender instanceof Player player) {
            return player.getLocation();
        }
        error(sender, "From console use: /battlepit setspawn <x> <y> <z> [world]");
        return null;
    }

    private void status(CommandSender sender) {
        List<String> names = pit.fighterNames();
        info(sender, "Battle pit: " + (plugin.getConfig().getBoolean("battlepit.enabled", true) ? "OPEN" : "closed")
                + " | spawn " + pit.formatLocation(pit.spawn())
                + " | " + pit.fighterCount() + " fighting");
        info(sender, "Kill reward " + plugin.getConfig().getInt("battlepit.kill-reward", 50)
                + " | death penalty " + plugin.getConfig().getInt("battlepit.death-penalty", 25)
                + " | exit " + pit.formatLocation(pit.exit())
                + " | exit-mode " + plugin.getConfig().getString("battlepit.exit-mode", "configured"));
        if (!names.isEmpty()) {
            info(sender, "In the pit: " + String.join(", ", names));
        }
    }

    private void stats(CommandSender sender, String[] args) {
        String name;
        UUID id = null;
        if (args.length >= 2) {
            Player online = Bukkit.getPlayerExact(args[1]);
            if (online != null) {
                name = online.getName();
                id = online.getUniqueId();
            } else {
                name = args[1];
            }
        } else if (sender instanceof Player player) {
            name = player.getName();
            id = player.getUniqueId();
        } else {
            error(sender, "Usage: /battlepit stats <player>");
            return;
        }

        PitStatsStore.Career career = id == null ? pit.careerByName(name) : pit.career(id);
        BattlePit.SessionStats session = id == null ? pit.statsByName(name) : pit.stats(id);
        if (career == null && (session == null || !session.hasPlayed())) {
            info(sender, "No battle pit stats for " + name + ".");
            return;
        }
        if (career != null) {
            info(sender, name + " — lifetime K/D " + career.kills() + "/" + career.deaths()
                    + " (" + career.kd() + ") | best streak " + career.bestStreak());
        }
        if (session != null && session.hasPlayed()) {
            info(sender, "This session — K/D " + session.kills() + "/" + session.deaths()
                    + " (" + session.kd() + ") | best streak " + session.bestStreak()
                    + " | points " + signed(session.points()));
        }
    }

    private void top(CommandSender sender, String[] args) {
        String metric = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "kills";
        if (!METRICS.contains(metric)) {
            error(sender, "Metric must be one of: " + String.join(", ", METRICS) + ".");
            return;
        }
        List<PitStatsStore.Career> top = pit.careerLeaderboard(metric, 10);
        if (top.isEmpty()) {
            info(sender, "No battle pit stats recorded yet.");
            return;
        }
        info(sender, "Battle pit top " + metric + " (all time):");
        int rank = 1;
        for (PitStatsStore.Career career : top) {
            int value = switch (metric) {
                case "deaths" -> career.deaths();
                case "streak" -> career.bestStreak();
                default -> career.kills();
            };
            info(sender, "  " + rank++ + ". " + career.name() + " — " + value);
        }
    }

    private void bounty(CommandSender sender, String[] args) {
        if (!sender.hasPermission("guab.battlepit.bounty")) {
            error(sender, "You need guab.battlepit.bounty to place a bounty.");
            return;
        }
        if (!(sender instanceof Player placer)) {
            error(sender, "Only players can place a bounty.");
            return;
        }
        if (args.length < 3) {
            error(sender, "Usage: /battlepit bounty <player> <amount>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            error(sender, "Player not online: " + args[1]);
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException exception) {
            error(sender, "Amount must be a whole number of points.");
            return;
        }
        pit.placeBounty(placer, target, amount);
    }

    private void kick(CommandSender sender, String[] args) {
        if (!sender.hasPermission("guab.battlepit.admin")) {
            error(sender, "You need guab.battlepit.admin to kick a fighter.");
            return;
        }
        if (args.length < 2) {
            error(sender, "Usage: /battlepit kick <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            error(sender, "Player not online: " + args[1]);
            return;
        }
        if (!pit.inPit(target)) {
            error(sender, target.getName() + " is not in the battle pit.");
            return;
        }
        pit.kick(target);
        info(sender, "Removed " + target.getName() + " from the battle pit.");
    }

    private void purge(CommandSender sender) {
        if (!sender.hasPermission("guab.battlepit.admin")) {
            error(sender, "You need guab.battlepit.admin to purge the pit.");
            return;
        }
        int removed = pit.purge();
        info(sender, "Purged " + removed + " battle pit fighter" + (removed == 1 ? "" : "s") + ".");
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("guab.battlepit.admin")) {
            error(sender, "You need guab.battlepit.admin to reload the pit.");
            return;
        }
        plugin.reloadConfig();
        info(sender, "Battle pit config reloaded.");
    }

    private void usage(CommandSender sender) {
        info(sender, "/battlepit join|leave|quit|status|stats [player]|top [kills|deaths|streak]|bounty <player> <amount>");
        if (sender.hasPermission("guab.battlepit.admin")) {
            info(sender, "/battlepit setspawn [x y z world]|setexit|kick <player>|purge|reload");
        }
    }

    private String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[BattlePit] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[BattlePit] ", NamedTextColor.RED)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("join", "leave", "quit", "status", "stats", "top"));
            if (sender.hasPermission("guab.battlepit.bounty")) {
                out.add("bounty");
            }
            if (sender.hasPermission("guab.battlepit.admin")) {
                out.addAll(List.of("setspawn", "setexit", "kick", "purge", "reload"));
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "stats" -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        out.add(player.getName());
                    }
                }
                case "top" -> out.addAll(METRICS);
                case "kick", "bounty" -> out.addAll(pit.fighterNames());
                default -> {
                    // No completions.
                }
            }
        } else if (args.length == 3 && "bounty".equalsIgnoreCase(args[0])) {
            out.addAll(List.of("50", "100", "250", "500"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
