package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * The physical dartboard. Right-clicking it opens the mode dialog while idle and
 * throws a dart while a leg is live. The actual scoring is in {@link DartsGeometry}.
 */
public final class DartsBoard implements Listener {

    private final GuabItemsPlugin plugin;

    public DartsBoard(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || event.getBlockFace() != plugin.darts().config().face()) {
            return;
        }
        Location point = interactionPoint(event);
        if (!plugin.darts().isBoard(block, point)) {
            return;
        }
        event.setCancelled(true);

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("guab.pub.darts.use")) {
            player.sendMessage(Component.text("[Darts] ", NamedTextColor.GOLD)
                    .append(Component.text("You are not allowed to play darts.", NamedTextColor.RED)));
            return;
        }
        Location centre = plugin.darts().boardCentre();
        if (centre == null) {
            player.sendMessage(Component.text("[Darts] ", NamedTextColor.GOLD)
                    .append(Component.text("The dartboard is not configured.", NamedTextColor.RED)));
            return;
        }
        if (!player.getWorld().equals(centre.getWorld())
                || player.getLocation().distanceSquared(centre)
                > plugin.darts().config().maxDistance() * plugin.darts().config().maxDistance()) {
            player.sendMessage(Component.text("[Darts] ", NamedTextColor.GOLD)
                    .append(Component.text("Stand closer to the oche.", NamedTextColor.RED)));
            return;
        }

        if (!plugin.darts().isPlaying(player.getUniqueId())) {
            if (player.isSneaking()) {
                plugin.darts().startDefault(player);
            } else {
                plugin.darts().openMenu(player);
            }
            return;
        }

        Location throwPoint = point != null ? point : rayPoint(player, centre);
        plugin.darts().throwDart(player, throwPoint);
    }

    private Location interactionPoint(PlayerInteractEvent event) {
        return event.getInteractionPoint();
    }

    /**
     * Fallback for servers where the interaction point is unavailable: trace the
     * player's eye ray into the board plane so aim is still respected.
     */
    private Location rayPoint(Player player, Location centre) {
        Vector direction = player.getEyeLocation().getDirection();
        RayTraceResult result = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(), direction, 16.0, org.bukkit.FluidCollisionMode.NEVER, true);
        if (result != null && result.getHitPosition() != null) {
            return result.getHitPosition().toLocation(player.getWorld());
        }
        return centre.clone();
    }
}
