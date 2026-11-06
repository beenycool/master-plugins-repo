package gg.guab.items;

import gg.guab.core.PointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
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
 * The Wetherspoons beer tap. Right-click the configured barrel to buy one pint for
 * points. The bartender is on their break, so it serves itself.
 */
public final class BeerTapListener implements Listener {

    private final GuabItemsPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public BeerTapListener(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
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
        long cooldown = Math.max(0, plugin.getConfig().getInt("beer.tap-cooldown-seconds", 2)) * 1000L;
        if (now - cooldowns.getOrDefault(player.getUniqueId(), 0L) < cooldown) {
            return;
        }

        int cost = Math.max(0, plugin.getConfig().getInt("beer.cost", 25));
        PointsService points = points();
        if (cost > 0) {
            if (points == null) {
                msg(player, "The till is broken; the points ledger is unavailable.", NamedTextColor.RED);
                return;
            }
            if (!points.take(player.getUniqueId(), cost)) {
                msg(player, "A pint is " + cost + " points and you cannot cover it.", NamedTextColor.RED);
                return;
            }
        }

        cooldowns.put(player.getUniqueId(), now);
        var beer = plugin.items().create(ItemType.BEER, 1);
        player.getInventory().addItem(beer).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.playSound(player.getLocation(), Sound.BLOCK_BARREL_OPEN, 1.0f, 1.0f);
        msg(player, "Pint of Wetherspoons poured"
                + (cost > 0 ? " for " + cost + " points" : " for free") + ".", NamedTextColor.GOLD);
    }

    private boolean isTap(Block block) {
        String world = plugin.getConfig().getString("beer.tap.world", "guab_casino");
        int x = plugin.getConfig().getInt("beer.tap.x", -20);
        int y = plugin.getConfig().getInt("beer.tap.y", 66);
        int z = plugin.getConfig().getInt("beer.tap.z", -14);
        return block.getWorld().getName().equals(world)
                && block.getX() == x
                && block.getY() == y
                && block.getZ() == z;
    }

    private PointsService points() {
        var registration = Bukkit.getServicesManager().getRegistration(PointsService.class);
        return registration == null ? null : registration.getProvider();
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
