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
            case "setup" -> setup(sender, args);
            case "dispatch" -> dispatch(sender, args);
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

    private void setup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("guab.casino.admin")) {
            error(sender, "You need guab.casino.admin to place the stations.");
            return;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
        } else if (Bukkit.getOnlinePlayers().size() == 1) {
            target = Bukkit.getOnlinePlayers().iterator().next();
        } else {
            target = null;
        }
        if (target == null) {
            error(sender, args.length >= 2
                    ? "Player " + args[1] + " is not online."
                    : "Specify a player: /casino setup <player>");
            return;
        }

        List<SetupStep> steps = List.of(
                new SetupStep(0.5, 65.0, 0.5, "rouletteadmin create"),
                new SetupStep(19.5, 65.0, -9.5, "slotsadmin create 3 1"),
                new SetupStep(19.5, 65.0, -4.5, "slotsadmin create 3 1"),
                new SetupStep(19.5, 65.0, 0.5, "slotsadmin create 3 1"),
                new SetupStep(19.5, 65.0, 7.5, "minegameadmin create"),
                new SetupStep(-14.5, 65.0, 12.5, "chickenadmin create 7"),
                new SetupStep(-14.5, 65.0, 0.5, "fightadmin create 9 2"));

        var world = plugin.venue().world();
        var home = target.getLocation().clone();
        target.closeInventory();
        info(sender, "Placing " + steps.size() + " stations as " + target.getName() + "...");
        runStep(sender, target, world, steps, 0, home);
    }

    private void runStep(CommandSender sender, Player player, org.bukkit.World world,
                         List<SetupStep> steps, int index, Location home) {
        if (!player.isOnline()) {
            error(sender, player.getName() + " left before the stations were placed.");
            return;
        }
        if (index >= steps.size()) {
            player.teleport(home);
            player.setFallDistance(0.0f);
            info(sender, "All stations placed. Walk in with /casino and check each table.");
            return;
        }
        SetupStep step = steps.get(index);
        Location at = new Location(world, step.x(), step.y(), step.z(), 180.0f, 0.0f);
        player.teleport(at);
        player.setFallDistance(0.0f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            boolean ran = Bukkit.dispatchCommand(player, step.command());
            info(sender, "  " + step.command() + " -> " + (ran ? "ran" : "not handled"));
            runStep(sender, player, world, steps, index + 1, home);
        }, 4L);
    }

    private record SetupStep(double x, double y, double z, String command) {
    }

    private void dispatch(CommandSender sender, String[] args) {
        if (!sender.hasPermission("guab.casino.admin")) {
            error(sender, "You need guab.casino.admin to dispatch commands.");
            return;
        }
        if (args.length < 3) {
            error(sender, "Usage: /casino dispatch <player> <command...>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            error(sender, "Player " + args[1] + " is not online.");
            return;
        }
        String command = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        boolean ran = Bukkit.dispatchCommand(target, command);
        info(sender, "Dispatched '" + command + "' as " + target.getName()
                + " (" + (ran ? "handled" : "not handled") + ").");
    }

    private void setSpawn(CommandSender sender) {        if (!sender.hasPermission("guab.casino.admin")) {
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
        info(sender, "/casino [enter|build|setup [player]|dispatch <player> <command>|setspawn|status|reload]");
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
                out.addAll(List.of("build", "setup", "dispatch", "setspawn", "status", "reload"));
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(value -> !value.startsWith(prefix));
        return out;
    }
}
