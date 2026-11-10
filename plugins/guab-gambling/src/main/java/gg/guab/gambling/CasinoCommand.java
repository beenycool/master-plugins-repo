package gg.guab.gambling;

import gg.guab.core.GuabFx;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
            case "leave", "exit" -> leave(sender);
            case "open" -> setEventEnabled(sender, true);
            case "close" -> setEventEnabled(sender, false);
            case "build" -> build(sender);
            case "setup" -> setup(sender, args);
            case "dispatch" -> dispatch(sender, args);
            case "setspawn" -> setSpawn(sender);
            case "status" -> status(sender);
            case "snapshot", "backup" -> snapshot(sender, args);
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
        if (!player.hasPermission("guab.casino.use")) {
            error(sender, "You need guab.casino.use to enter the casino.");
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
        GuabFx.arrival(player, "CASINO", NamedTextColor.GOLD, Sound.BLOCK_ENDER_CHEST_OPEN, 0.7f);
        info(sender, "Welcome to the Guab Casino. Play safe, lose everything.");
    }

    /** /casino leave|exit returns the player to the configured exit or world spawn. */
    private void leave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can leave the casino.");
            return;
        }
        player.closeInventory();
        player.teleport(exitLocation());
        player.setFallDistance(0.0f);
        GuabFx.arrival(player, "OUTSIDE", NamedTextColor.GRAY, Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.8f);
        info(sender, "You step out of the casino.");
    }

    private Location exitLocation() {
        var cfg = plugin.getConfig();
        String worldName = cfg.getString("venue.exit-world", "");
        if (worldName != null && !worldName.isBlank()) {
            org.bukkit.World world = Bukkit.getWorld(worldName);
            if (world != null) {
                return new Location(world,
                        cfg.getDouble("venue.exit-x", 0.5),
                        cfg.getDouble("venue.exit-y", 64.0),
                        cfg.getDouble("venue.exit-z", 0.5),
                        (float) cfg.getDouble("venue.exit-yaw", 0.0),
                        (float) cfg.getDouble("venue.exit-pitch", 0.0));
            }
        }
        var worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return plugin.venue().spawn();
        }
        return worlds.get(0).getSpawnLocation();
    }

    /** /casino open|close flips event.enabled, reloads and tells everyone. */
    private void setEventEnabled(CommandSender sender, boolean enabled) {
        if (!sender.hasPermission("guab.casino.admin")) {
            error(sender, "You need guab.casino.admin to open or close the casino.");
            return;
        }
        plugin.getConfig().set("event.enabled", enabled);
        plugin.saveConfig();
        plugin.reload();
        Bukkit.broadcast(Component.text("[Casino] ", NamedTextColor.GOLD)
                .append(Component.text(enabled
                        ? "The casino is now OPEN. Good luck."
                        : "The casino is now closed.", NamedTextColor.WHITE)));
        info(sender, enabled ? "Event enabled and casino reloaded." : "Event disabled and casino reloaded.");
    }

    private void build(CommandSender sender) {
        if (!sender.hasPermission("guab.casino.admin")) {
            error(sender, "You need guab.casino.admin to build the casino.");
            return;
        }
        int blocks = plugin.venue().build();
        info(sender, "Casino rebuilt in " + plugin.config().worldName() + " at "
                + plugin.config().x() + ", " + plugin.config().y() + ", " + plugin.config().z()
                + " (" + blocks + " blocks cleared; pub area preserved).");
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
        if (!player.teleport(at)) {
            error(sender, "Could not move " + player.getName() + " to " + step.command()
                    + "; stopping setup.");
            player.teleport(home);
            return;
        }
        player.setFallDistance(0.0f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                boolean ran = Bukkit.dispatchCommand(player, step.command());
                info(sender, "  " + step.command() + " -> " + (ran ? "ran" : "not handled"));
                runStep(sender, player, world, steps, index + 1, home);
            } catch (RuntimeException exception) {
                // Never strand the setup player on a pad when MineGames or another
                // station plugin is missing/disabled.
                error(sender, step.command() + " failed: " + exception.getMessage()
                        + "; returning " + player.getName() + " to their start.");
                player.teleport(home);
                player.setFallDistance(0.0f);
            }
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
        if (!sender.hasPermission("guab.casino.admin")) {
            error(sender, "You need guab.casino.admin to inspect the casino.");
            return;
        }
        var mineGames = Bukkit.getPluginManager().getPlugin("MineGames");
        var vault = Bukkit.getPluginManager().getPlugin("Vault");
        var points = Bukkit.getServicesManager().getRegistration(gg.guab.core.PointsService.class);
        boolean worldLoaded = Bukkit.getWorld(plugin.config().worldName()) != null;
        int activeHands = plugin.blackjack() == null ? 0 : plugin.blackjack().activeHands();
        boolean blackjackOpen = plugin.getConfig().getBoolean("blackjack.enabled", true);
        boolean guiOpen = plugin.getConfig().getBoolean("blackjack.gui.enabled", true);

        info(sender, "Casino: " + (plugin.config().enabled() ? "OPEN" : "closed")
                + " | event.enabled " + plugin.config().enabled()
                + " | plugin " + state(plugin.isEnabled()));
        info(sender, "World: " + plugin.config().worldName() + " (" + (worldLoaded ? "loaded" : "not loaded") + ")"
                + " | spawn " + plugin.config().spawnX() + ", " + plugin.config().spawnY() + ", " + plugin.config().spawnZ());
        info(sender, "Blackjack: " + (blackjackOpen ? "enabled" : "disabled")
                + " | GUI " + (guiOpen ? "enabled" : "disabled")
                + " | active hands " + activeHands);
        info(sender, "Dealer: " + (plugin.dealer() == null ? "missing" : plugin.dealer().describe()));
        info(sender, "MineGames: " + (mineGames == null ? "missing" : state(mineGames.isEnabled()))
                + " | Vault: " + (vault == null ? "missing" : state(vault.isEnabled()))
                + " | PointsService: " + (points == null ? "missing" : "registered"));
    }

    private String state(boolean enabled) {
        return enabled ? "enabled" : "disabled";
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("guab.casino.admin")) {
            error(sender, "You need guab.casino.admin to reload the casino.");
            return;
        }
        plugin.reload();
        info(sender, "Casino config reloaded.");
    }

    /**
     * Zips the points ledger and every casino-side config into {@code data/backups}, so a
     * staff member can take a pre-event snapshot without touching the shell.
     */
    private void snapshot(CommandSender sender, String[] args) {
        if (!sender.hasPermission("guab.casino.admin")) {
            error(sender, "You need guab.casino.admin to take a snapshot.");
            return;
        }
        Path data = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        Path folder = data.resolve("backups");
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            listSnapshots(sender, folder);
            return;
        }
        // Flush the live ledger first: the 60-second save timer may otherwise leave the
        // snapshot with a balance that is a minute behind the table.
        try {
            if (Bukkit.getPluginCommand("guabcore") != null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "guabcore save");
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not flush GuabCore before snapshot: "
                    + exception.getMessage());
        }
        Path plugins = data.resolve("plugins");
        List<Path> sources = new ArrayList<>();
        addIfFile(sources, plugins.resolve("GuabCore/points.yml"));
        addIfFile(sources, plugins.resolve("GuabCore/config.yml"));
        addIfFile(sources, plugins.resolve("GuabGambling/config.yml"));
        Path mineGames = plugins.resolve("MineGames");
        if (Files.isDirectory(mineGames)) {
            try (Stream<Path> files = Files.list(mineGames)) {
                files.filter(path -> path.getFileName().toString().endsWith(".yml")).forEach(sources::add);
            } catch (IOException exception) {
                error(sender, "Could not list MineGames configs: " + exception.getMessage());
                return;
            }
        }
        if (sources.isEmpty()) {
            error(sender, "Nothing to snapshot yet; plugin data is missing.");
            return;
        }
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path out = data.resolve("backups").resolve("casino-snapshot-" + stamp + ".zip");
        try {
            Files.createDirectories(out.getParent());
            try (OutputStream stream = Files.newOutputStream(out);
                 ZipOutputStream zip = new ZipOutputStream(stream)) {
                for (Path source : sources) {
                    zip.putNextEntry(new ZipEntry(data.relativize(source).toString().replace('\\', '/')));
                    Files.copy(source, zip);
                    zip.closeEntry();
                }
            }
        } catch (IOException exception) {
            error(sender, "Snapshot failed: " + exception.getMessage());
            return;
        }
        info(sender, "Snapshot saved to " + out + " (" + sources.size() + " files).");
    }

    private void listSnapshots(CommandSender sender, Path folder) {
        if (!Files.isDirectory(folder)) {
            info(sender, "No casino snapshots yet.");
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> snapshots = files
                    .filter(path -> path.getFileName().toString().startsWith("casino-snapshot-"))
                    .filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted(java.util.Comparator.comparing(Path::getFileName).reversed())
                    .toList();
            if (snapshots.isEmpty()) {
                info(sender, "No casino snapshots yet.");
                return;
            }
            info(sender, "Casino snapshots (" + snapshots.size() + "):");
            for (Path snapshot : snapshots) {
                long bytes = Files.size(snapshot);
                sender.sendMessage(Component.text("  " + snapshot.getFileName()
                                + "  ", NamedTextColor.WHITE)
                        .append(Component.text(String.format(Locale.ROOT, "%,d KB", bytes / 1024),
                                NamedTextColor.GRAY)));
            }
        } catch (IOException exception) {
            error(sender, "Could not list snapshots: " + exception.getMessage());
        }
    }

    private void addIfFile(List<Path> sources, Path path) {
        if (Files.isRegularFile(path)) {
            sources.add(path);
        }
    }

    private void usage(CommandSender sender) {
        info(sender, "/casino [enter|leave|open|close|build|setup [player]|dispatch <player> <command>|setspawn|status|snapshot [list]|reload]");
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
            out.addAll(List.of("enter", "leave", "exit"));
            if (sender.hasPermission("guab.casino.admin")) {
                out.addAll(List.of("open", "close", "build", "setup", "dispatch",
                        "setspawn", "status", "snapshot", "reload"));
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("snapshot")) {
            out.add("list");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("setup")
                || args[0].equalsIgnoreCase("dispatch"))) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                out.add(player.getName());
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(value -> !value.startsWith(prefix));
        return out;
    }
}
