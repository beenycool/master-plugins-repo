package gg.guab.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

/**
 * Small operator surface for the shared core: flush the ledgers, inspect what is
 * loaded, and reload the config without restarting the server.
 */
public final class CoreAdminCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "guab.core.admin";
    private static final List<String> SUBCOMMANDS = List.of("save", "status", "reload");

    private final GuabCorePlugin plugin;

    public CoreAdminCommand(GuabCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            error(sender, "You need " + PERMISSION + " to manage GuabCore.");
            return true;
        }
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "save", "flush" -> save(sender);
            case "status", "info" -> status(sender);
            case "reload" -> reload(sender);
            default -> usage(sender, label);
        }
        return true;
    }

    private void save(CommandSender sender) {
        try {
            plugin.saveNow();
            info(sender, "Points, stats and player preferences flushed to disk.");
        } catch (RuntimeException exception) {
            error(sender, "Save failed: " + exception.getMessage());
        }
    }

    private void reload(CommandSender sender) {
        try {
            plugin.reloadCore();
            info(sender, "GuabCore config reloaded.");
            info(sender, "HUD enable/update-tick changes still need a server restart; "
                    + "sidebar lines, tab text and welcome messages apply immediately.");
        } catch (RuntimeException exception) {
            error(sender, "Reload failed: " + exception.getMessage());
        }
    }

    private void status(CommandSender sender) {
        PointsStore points = plugin.pointsStore();
        StatsStore stats = plugin.statsStore();
        boolean vault = plugin.getServer().getPluginManager().isPluginEnabled("Vault");
        info(sender, "GuabCore " + plugin.getPluginMeta().getVersion()
                + " | points " + (points.loadFailed() ? "LOAD FAILED" : "ok"));
        info(sender, "Balances: " + points.size()
                + " | stat players: " + stats.knownPlayers().size()
                + " | saved every " + plugin.getConfig().getInt("save-interval-seconds", 60) + "s");
        info(sender, "HUD: " + (plugin.hudEnabled() ? "enabled" : "disabled")
                + " | update " + plugin.getConfig().getInt("hud.update-ticks", 20) + " ticks"
                + " | font icons " + plugin.getConfig().getBoolean("hud.font-icons", true));
        info(sender, "Vault: " + (vault ? "enabled" : "missing")
                + " | preferences: " + plugin.preferences().folder());
        if (points.loadFailed()) {
            error(sender, "points.yml was not loaded: " + points.loadError()
                    + " (kept as points.yml.bak until fixed)");
        }
    }

    private void usage(CommandSender sender, String label) {
        info(sender, "/" + label + " save|status|reload");
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[GuabCore] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[GuabCore] ", NamedTextColor.RED)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION) || args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
