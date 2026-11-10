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

/**
 * The pub dartboard is the entry point. Right-clicking it opens the mode dialog
 * and sends the player to the dedicated bow-and-target room. Scoring happens on
 * arrow impact in {@link DartsRoom}.
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
        Location point = event.getInteractionPoint();
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

        if (plugin.darts().isPlaying(player.getUniqueId())) {
            // Contextual Resume / Quit / My stats dialog instead of a dead end.
            plugin.darts().openMenu(player);
            return;
        }
        if (player.isSneaking()) {
            plugin.darts().startDefault(player);
        } else {
            plugin.darts().openMenu(player);
        }
    }
}
