package gg.guab.minigames;

import gg.guab.core.GuabFx;
import gg.guab.core.StatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Teleports players to the parkour start, configurable in config.yml. */
public final class ParkourCommand implements CommandExecutor, TabCompleter, Listener {

    private final GuabMinigamesPlugin plugin;
    private final Map<UUID, Location> returnLocations = new HashMap<>();

    public ParkourCommand(GuabMinigamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "set" -> {
                    set(sender);
                    return true;
                }
                case "reload" -> {
                    reload(sender);
                    return true;
                }
                case "quit" -> {
                    quit(sender);
                    return true;
                }
                case "restart" -> {
                    if (sender instanceof Player player) {
                        start(player);
                    } else {
                        error(sender, "Only players can restart the parkour.");
                    }
                    return true;
                }
                case "status" -> {
                    status(sender);
                    return true;
                }
                default -> {
                    // Fall through to the plain teleport.
                }
            }
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can teleport to the parkour.");
            return true;
        }
        start(player);
        return true;
    }

    private void start(Player player) {
        if (!player.hasPermission("guab.parkour.use")) {
            error(player, "You are not allowed to use the parkour.");
            return;
        }
        Location location = location();
        if (location == null) {
            String worldName = configuredWorldName();
            error(player, "The parkour world '" + worldName
                    + "' is not loaded. An admin can load that world or run /parkour set.");
            return;
        }
        // Keep the first return point so repeatedly using /parkour does not trap you.
        returnLocations.putIfAbsent(player.getUniqueId(), player.getLocation().clone());
        player.teleport(location);
        player.setFallDistance(0.0f);
        GuabFx.arrival(player, "PARKOUR", NamedTextColor.GREEN, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f);
        var registration = plugin.getServer().getServicesManager().getRegistration(StatsService.class);
        if (registration != null) {
            registration.getProvider().increment(player, StatsService.PARKOUR, 1);
        }
        player.sendMessage(prefix().append(Component.text(
                "Welcome to the parkour. Use /parkour quit to return.", NamedTextColor.GOLD)));
    }

    private void quit(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can leave the parkour.");
            return;
        }
        Location back = returnLocations.remove(player.getUniqueId());
        if (back == null || back.getWorld() == null) {
            error(sender, "You have no saved parkour return location.");
            return;
        }
        player.teleport(back);
        player.setFallDistance(0.0f);
        GuabFx.arrival(player, "BACK", NamedTextColor.AQUA, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f);
        info(sender, "Returned to " + format(back) + ".");
    }

    private void status(CommandSender sender) {
        String worldName = configuredWorldName();
        World world = Bukkit.getWorld(worldName);
        info(sender, "Parkour world: " + worldName + (world == null ? " (NOT LOADED)" : " (loaded)"));
        Location location = location();
        info(sender, "Parkour start: " + (location == null ? "not available" : format(location)));
        if (sender instanceof Player player) {
            Location back = returnLocations.get(player.getUniqueId());
            info(sender, "Your return point: " + (back == null ? "not set" : format(back)));
        }
    }

    private String configuredWorldName() {
        return plugin.getConfig().getString("parkour.world", "world");
    }

    private Location location() {
        FileConfiguration config = plugin.getConfig();
        World world = Bukkit.getWorld(configuredWorldName());
        if (world == null) {
            // Never silently fall back to the first world: that teleports players
            // into an unrelated map when a world is renamed or not loaded.
            return null;
        }
        return new Location(world,
                config.getDouble("parkour.x", -8.0),
                config.getDouble("parkour.y", -59.0),
                config.getDouble("parkour.z", 9.0),
                (float) config.getDouble("parkour.yaw", 90.0),
                (float) config.getDouble("parkour.pitch", 0.0));
    }

    private void set(CommandSender sender) {
        if (!sender.hasPermission("guab.parkour.admin")) {
            error(sender, "You need guab.parkour.admin to move the parkour start.");
            return;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Run this in game.");
            return;
        }
        Location location = player.getLocation();
        FileConfiguration config = plugin.getConfig();
        config.set("parkour.world", location.getWorld().getName());
        config.set("parkour.x", location.getX());
        config.set("parkour.y", location.getY());
        config.set("parkour.z", location.getZ());
        config.set("parkour.yaw", location.getYaw());
        config.set("parkour.pitch", location.getPitch());
        plugin.saveConfig();
        info(sender, "Parkour start set to " + format(location) + ".");
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("guab.parkour.admin")) {
            error(sender, "You need guab.parkour.admin to reload the parkour.");
            return;
        }
        plugin.reloadConfig();
        info(sender, "Parkour config reloaded.");
    }

    private String format(Location location) {
        return location.getWorld().getName() + " "
                + String.format(Locale.ROOT, "%.1f, %.1f, %.1f",
                location.getX(), location.getY(), location.getZ());
    }

    private Component prefix() {
        return Component.text("[Parkour] ", NamedTextColor.AQUA);
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.WHITE)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        returnLocations.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(List.of("restart", "quit", "status"));
        if (sender.hasPermission("guab.parkour.admin")) {
            options.addAll(List.of("set", "reload"));
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.startsWith(prefix));
        return options;
    }
}
