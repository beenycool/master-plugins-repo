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

public final class BattlePitCommand implements CommandExecutor, TabCompleter {

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
                + " | exit " + pit.formatLocation(pit.exit()));
        if (!names.isEmpty()) {
            info(sender, "In the pit: " + String.join(", ", names));
        }
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
        info(sender, "/battlepit join|leave|status");
        if (sender.hasPermission("guab.battlepit.admin")) {
            info(sender, "/battlepit setspawn [x y z world]|setexit|reload");
        }
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
            out.addAll(List.of("join", "leave", "status"));
            if (sender.hasPermission("guab.battlepit.admin")) {
                out.addAll(List.of("setspawn", "setexit", "reload"));
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(value -> !value.startsWith(prefix));
        return out;
    }
}
