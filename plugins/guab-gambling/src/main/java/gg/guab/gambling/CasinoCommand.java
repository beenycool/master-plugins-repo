package gg.guab.gambling;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CasinoCommand implements CommandExecutor, TabCompleter {

    private final GuabGamblingPlugin plugin;

    public CasinoCommand(GuabGamblingPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            enter(sender, false);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "enter", "tp" -> enter(sender, true);
            case "build" -> build(sender);
            case "setspawn" -> setSpawn(sender);
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void enter(CommandSender sender, boolean adminOverride) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can walk into the casino.");
            return;
        }
        boolean allowed = plugin.config().enabled() || (adminOverride && player.hasPermission("guab.casino.admin"));
        if (!allowed) {
            error(sender, "The casino is closed. An admin can open it with event.enabled in the config.");
            return;
        }
        Location spawn = plugin.venue().spawn();
        player.teleport(spawn);
        player.setFallDistance(0.0f);
        info(sender, "Welcome to the Guab Casino. Play safe, lose everything.");
    }

    private void build(CommandSender sender) {
        if (!sender.hasPermission("guab.casino.admin")) {
            error(sender, "You need guab.casino.admin to build the casino.");
            return;
        }
        int blocks = plugin.venue().build();
        info(sender, "Casino rebuilt in " + plugin.config().worldName() + " at "
                + plugin.config().x() + ", " + plugin.config().y() + ", " + plugin.config().z()
                + " (" + blocks + " blocks cleared).");
        info(sender, "Now stand on each coloured pad in game and run:");
        info(sender, "  /rouletteadmin create   (red, centre)");
        info(sender, "  /slotsadmin create 3 1  (lime, three pads)");
        info(sender, "  /minegameadmin create   (blue, on the beacon)");
        info(sender, "  /chickenadmin create 7  (yellow)");
        info(sender, "  /fightadmin create 9 2  (orange, arena origin)");
        info(sender, "Then set the event open with event.enabled: true.");
    }

    private void setSpawn(CommandSender sender) {
        if (!sender.hasPermission("guab.casino.admin")) {
            error(sender, "You need guab.casino.admin to move the casino spawn.");
            return;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Run this in game.");
            return;
        }
        plugin.getConfig().set("venue.spawn-x", player.getLocation().getBlockX());
        plugin.getConfig().set("venue.spawn-y", player.getLocation().getBlockY());
        plugin.getConfig().set("venue.spawn-z", player.getLocation().getBlockZ());
        plugin.saveConfig();
        plugin.reload();
        info(sender, "Casino spawn moved to " + player.getLocation().getBlockX() + ", "
                + player.getLocation().getBlockY() + ", " + player.getLocation().getBlockZ() + ".");
    }

    private void status(CommandSender sender) {
        var mineGames = Bukkit.getPluginManager().getPlugin("MineGames");
        var vault = Bukkit.getPluginManager().getPlugin("Vault");
        var points = Bukkit.getServicesManager().getRegistration(gg.guab.core.PointsService.class);
        info(sender, "Casino: " + (plugin.config().enabled() ? "OPEN" : "closed")
                + " | world " + plugin.config().worldName()
                + " | spawn " + plugin.config().spawnX() + ", " + plugin.config().spawnY() + ", " + plugin.config().spawnZ());
        info(sender, "MineGames: " + (mineGames == null ? "missing" : "installed")
                + " | Vault: " + (vault == null ? "missing" : "installed")
                + " | PointsService: " + (points == null ? "missing" : "registered"));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("guab.casino.admin")) {
            error(sender, "You need guab.casino.admin to reload the casino.");
            return;
        }
        plugin.reload();
        info(sender, "Casino config reloaded.");
    }

    private void usage(CommandSender sender) {
        info(sender, "/casino [enter|build|setspawn|status|reload]");
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Casino] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Casino] ", NamedTextColor.RED)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("enter");
            if (sender.hasPermission("guab.casino.admin")) {
                out.addAll(List.of("build", "setspawn", "status", "reload"));
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(value -> !value.startsWith(prefix));
        return out;
    }
}
