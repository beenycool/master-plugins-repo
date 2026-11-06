package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Wetherspoons beer tap. Right-click the barrel for a cheap house pint;
 * sneak-right-click opens the full 26.3 beer menu.
 */
public final class BeerTapListener implements Listener {

    private final GuabItemsPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public BeerTapListener(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !isTap(block)) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!player.hasPermission("guab.items.use")) {
            msg(player, "You are not old enough to drink here.", NamedTextColor.RED);
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = plugin.config().beerTapCooldownSeconds() * 1000L;
        if (now - cooldowns.getOrDefault(player.getUniqueId(), 0L) < cooldown) {
            return;
        }

        if (!player.isSneaking()) {
            cooldowns.put(player.getUniqueId(), now);
            plugin.beerMenu().open(player);
            return;
        }

        // Shift-right-click is the quick "house pint" shortcut: random style,
        // cheap tap price. Normal right-click opens the picker.
        BeerStyle style = plugin.beer().randomHouse();
        int cost = Math.max(0, plugin.getConfig().getInt("beer.cost", 1));
        if (plugin.beerService().purchase(player, style, 1, cost, false)) {
            cooldowns.put(player.getUniqueId(), now);
        }
    }

    private boolean isTap(Block block) {
        if (block.getType() != Material.BARREL) {
            return false;
        }
        var centre = plugin.pub().centre();
        if (centre != null && block.getWorld().equals(centre.getWorld())) {
            double radius = Math.max(4.0, plugin.getConfig().getDouble("beer.tap.radius", 12.0));
            return block.getLocation().distanceSquared(centre) <= radius * radius;
        }
        String world = plugin.getConfig().getString("beer.tap.world", "guab_casino");
        int x = plugin.getConfig().getInt("beer.tap.x", -20);
        int y = plugin.getConfig().getInt("beer.tap.y", 66);
        int z = plugin.getConfig().getInt("beer.tap.z", -14);
        return block.getWorld().getName().equals(world)
                && block.getX() == x
                && block.getY() == y
                && block.getZ() == z;
    }

    private void msg(Player player, String message, NamedTextColor color) {
        player.sendMessage(Component.text("[Spoons] ", NamedTextColor.GOLD)
                .append(Component.text(message, color)));
    }

    /** Used by /guabitems tapinfo so staff can check where the tap is. */
    public Location tapLocation() {
        var world = Bukkit.getWorld(plugin.getConfig().getString("beer.tap.world", "guab_casino"));
        if (world == null) {
            return null;
        }
        return new Location(world,
                plugin.getConfig().getInt("beer.tap.x", -20),
                plugin.getConfig().getInt("beer.tap.y", 66),
                plugin.getConfig().getInt("beer.tap.z", -14));
    }
}
