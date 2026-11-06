package gg.guab.minigames;

import gg.guab.core.StatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Locale;

/** Teleports players to the parkour start, configurable in config.yml. */
public final class ParkourCommand implements CommandExecutor {

    private final GuabMinigamesPlugin plugin;

    public ParkourCommand(GuabMinigamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "set" -> {
                    set(sender);
                    return true;
                }
                case "reload" -> {
                    reload(sender);
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
        if (!player.hasPermission("guab.parkour.use")) {
            error(sender, "You are not allowed to use the parkour.");
            return true;
        }
        Location location = location();
        if (location == null) {
            error(sender, "The parkour start is not configured.");
            return true;
        }
        player.teleport(location);
        player.setFallDistance(0.0f);
        var registration = plugin.getServer().getServicesManager().getRegistration(StatsService.class);
        if (registration != null) {
            registration.getProvider().increment(player, StatsService.PARKOUR, 1);
        }
        player.sendMessage(prefix().append(Component.text(
                "Welcome to the parkour.", NamedTextColor.GOLD)));
        return true;
    }

    private Location location() {
        FileConfiguration config = plugin.getConfig();
        String worldName = config.getString("parkour.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().getFirst();
        }
        if (world == null) {
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
}
