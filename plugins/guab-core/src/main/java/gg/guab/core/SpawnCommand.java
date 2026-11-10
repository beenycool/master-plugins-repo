package gg.guab.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /spawn} and {@code /hub}. Both use the same configurable point so the
 * hub can be moved without changing code; the items hub stays on {@code /guab}.
 */
public final class SpawnCommand implements CommandExecutor, TabCompleter {

    private final GuabCorePlugin plugin;
    private final PlayerPreferencesStore preferences;

    public SpawnCommand(GuabCorePlugin plugin, PlayerPreferencesStore preferences) {
        this.plugin = plugin;
        this.preferences = preferences;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can teleport to spawn.");
            return true;
        }
        if (!player.hasPermission("guab.spawn.use")) {
            error(sender, "You need guab.spawn.use to teleport.");
            return true;
        }
        Location target = location();
        if (target == null) {
            error(sender, "No spawn world is available yet.");
            return true;
        }
        String destination = command.getName().equalsIgnoreCase("hub") ? "hub" : "spawn";
        player.teleportAsync(target).thenAccept(success ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (!success) {
                        error(player, "Could not teleport you to the " + destination + ".");
                        return;
                    }
                    if (preferences.sounds(player.getUniqueId())) {
                        player.playSound(player.getLocation(),
                                Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS,
                                0.6f, 1.1f);
                    }
                    player.sendMessage(Component.text("[Guab] ", NamedTextColor.GOLD)
                            .append(Component.text("Welcome to the " + destination + ".",
                                    NamedTextColor.GRAY)));
                }));
        return true;
    }

    private Location location() {
        World world = resolveWorld();
        if (world == null) {
            return null;
        }
        if (plugin.getConfig().isSet("spawn.x")
                && plugin.getConfig().isSet("spawn.y")
                && plugin.getConfig().isSet("spawn.z")) {
            return new Location(world,
                    plugin.getConfig().getDouble("spawn.x"),
                    plugin.getConfig().getDouble("spawn.y"),
                    plugin.getConfig().getDouble("spawn.z"),
                    (float) plugin.getConfig().getDouble("spawn.yaw", 0.0),
                    (float) plugin.getConfig().getDouble("spawn.pitch", 0.0));
        }
        return world.getSpawnLocation();
    }

    private World resolveWorld() {
        String configured = plugin.getConfig().getString("spawn.world", "");
        if (configured != null && !configured.isBlank()) {
            World world = Bukkit.getWorld(configured);
            if (world != null) {
                return world;
            }
            plugin.getLogger().warning("spawn.world '" + configured
                    + "' is not loaded; using the first world instead.");
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Guab] ", NamedTextColor.RED)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        return List.of();
    }
}
