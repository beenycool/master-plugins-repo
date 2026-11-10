package gg.guab.minigames;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shows and changes the kill plane without editing config.yml by hand. */
public final class KillPlaneCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "guab.killplane.admin";
    private static final String STATUS_PERMISSION = "guab.killplane.status";

    private final GuabMinigamesPlugin plugin;
    private final KillPlane killPlane;

    public KillPlaneCommand(GuabMinigamesPlugin plugin, KillPlane killPlane) {
        this.plugin = plugin;
        this.killPlane = killPlane;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("status")) {
            if (!sender.hasPermission(PERMISSION) && !sender.hasPermission(STATUS_PERMISSION)) {
                error(sender, "You need " + STATUS_PERMISSION + " to view the kill plane.");
                return true;
            }
            status(sender);
            return true;
        }
        if (!sender.hasPermission(PERMISSION)) {
            error(sender, "You need " + PERMISSION + " to change the kill plane.");
            return true;
        }
        switch (sub) {
            case "on" -> {
                killPlane.setEnabled(true);
                info(sender, "Kill plane enabled at Y " + format(killPlane.threshold()) + ".");
            }
            case "off" -> {
                killPlane.setEnabled(false);
                info(sender, "Kill plane disabled.");
            }
            case "set" -> {
                if (args.length < 2) {
                    error(sender, "Usage: /" + label + " set <y>");
                    return true;
                }
                double y;
                try {
                    y = Double.parseDouble(args[1]);
                } catch (NumberFormatException exception) {
                    error(sender, "Y must be a number.");
                    return true;
                }
                if (!Double.isFinite(y)) {
                    error(sender, "Y must be a finite number (not NaN or Infinity).");
                    return true;
                }
                killPlane.setThreshold(y);
                killPlane.setEnabled(true);
                info(sender, "Kill plane set to Y " + format(y) + " and enabled.");
            }
            case "reload" -> {
                plugin.reloadConfig();
                info(sender, "Kill plane config reloaded (Y " + format(killPlane.threshold()) + ").");
            }
            case "world", "worlds" -> world(sender, args, label);
            default -> error(sender, "Usage: /" + label + " [on|off|set <y>|world add|remove|list|reload|status]");
        }
        return true;
    }

    private void world(CommandSender sender, String[] args, String label) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list" -> info(sender, "Kill plane worlds: " + worldsText() + ".");
            case "add" -> {
                if (args.length < 3) {
                    error(sender, "Usage: /" + label + " world add <world>");
                    return;
                }
                if (!killPlane.addWorld(args[2])) {
                    error(sender, "Unknown or unloaded world: " + args[2]);
                    return;
                }
                info(sender, "Added " + args[2] + " to the kill plane.",
                        "Kill plane worlds: " + worldsText() + ".");
            }
            case "remove" -> {
                if (args.length < 3) {
                    error(sender, "Usage: /" + label + " world remove <world>");
                    return;
                }
                if (!killPlane.removeWorld(args[2])) {
                    error(sender, args[2] + " was not in the kill plane world list.");
                    return;
                }
                info(sender, "Removed " + args[2] + " from the kill plane.",
                        "Kill plane worlds: " + worldsText() + ".");
            }
            default -> error(sender, "Usage: /" + label + " world <add|remove|list> [world]");
        }
    }

    private void status(CommandSender sender) {
        info(sender, "Kill plane: " + (killPlane.enabled() ? "enabled" : "disabled")
                + " at Y " + format(killPlane.threshold()) + ".");
        String worlds = worldsText();
        String gamemodes = killPlane.ignoredGamemodes().isEmpty()
                ? "none"
                : String.join(", ", killPlane.ignoredGamemodes());
        info(sender, "Worlds: " + worlds + " | ignored gamemodes: " + gamemodes + ".");
        if (sender instanceof Player player) {
            info(sender, "Your Y: " + format(player.getLocation().getY())
                    + " | bypass: " + (player.hasPermission("guab.killplane.bypass") ? "yes" : "no"));
        } else {
            info(sender, "Current Y: not available from console.");
        }
    }

    private String worldsText() {
        return killPlane.worlds().isEmpty() ? "all worlds" : String.join(", ", killPlane.worlds());
    }

    private String format(double y) {
        return String.format(Locale.ROOT, "%.1f", y);
    }

    private void info(CommandSender sender, String... messages) {
        for (String message : messages) {
            sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.WHITE)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission(PERMISSION);
        boolean status = admin || sender.hasPermission(STATUS_PERMISSION);
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (status) {
                options.add("status");
            }
            if (admin) {
                options.addAll(List.of("on", "off", "set", "world", "reload"));
            }
            return filter(options, args[0]);
        }
        if (!admin) {
            return List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            List<String> options = new ArrayList<>(List.of("-66", "-64", "-62"));
            if (sender instanceof Player player) {
                options.addFirst(format(player.getLocation().getY()));
            }
            return filter(options, args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("world") || args[0].equalsIgnoreCase("worlds"))) {
            return filter(List.of("add", "remove", "list"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("world")
                && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            List<String> names = new ArrayList<>(killPlane.worlds());
            for (World world : Bukkit.getWorlds()) {
                if (names.stream().noneMatch(name -> name.equalsIgnoreCase(world.getName()))) {
                    names.add(world.getName());
                }
            }
            return filter(names, args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }

    private Component prefix() {
        return Component.text("[KillPlane] ", NamedTextColor.DARK_RED);
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }
}
