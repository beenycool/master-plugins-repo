package gg.guab.minigames;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shows and changes the kill plane without editing config.yml by hand. */
public final class KillPlaneCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "guab.killplane.admin";

    private final GuabMinigamesPlugin plugin;
    private final KillPlane killPlane;

    public KillPlaneCommand(GuabMinigamesPlugin plugin, KillPlane killPlane) {
        this.plugin = plugin;
        this.killPlane = killPlane;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            error(sender, "You need " + PERMISSION + " to change the kill plane.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            status(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
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
                killPlane.setThreshold(y);
                killPlane.setEnabled(true);
                info(sender, "Kill plane set to Y " + format(y) + " and enabled.");
            }
            case "reload" -> {
                plugin.reloadConfig();
                info(sender, "Kill plane config reloaded (Y " + format(killPlane.threshold()) + ").");
            }
            default -> error(sender, "Usage: /" + label + " [on|off|set <y>|reload|status]");
        }
        return true;
    }

    private void status(CommandSender sender) {
        info(sender, "Kill plane: " + (killPlane.enabled() ? "enabled" : "disabled")
                + " at Y " + format(killPlane.threshold()) + ".");
        String worlds = killPlane.worlds().isEmpty() ? "all worlds" : String.join(", ", killPlane.worlds());
        info(sender, "Worlds: " + worlds + ".");
    }

    private String format(double y) {
        return String.format(Locale.ROOT, "%.1f", y);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("on", "off", "set", "reload", "status"));
            options.removeIf(option -> !option.startsWith(args[0].toLowerCase(Locale.ROOT)));
            return options;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return List.of("-63", "-62", "-64");
        }
        return List.of();
    }

    private Component prefix() {
        return Component.text("[KillPlane] ", NamedTextColor.DARK_RED);
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.WHITE)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }
}
