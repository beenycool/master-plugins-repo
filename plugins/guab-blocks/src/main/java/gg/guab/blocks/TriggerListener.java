package gg.guab.blocks;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class TriggerListener implements Listener {

    private final GuabBlocksPlugin plugin;

    public TriggerListener(GuabBlocksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.config().enabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("guab.blocks.bypass")) {
            return;
        }
        if (plugin.pranks().isExcluded(player)) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }

        Block previous = from.getBlock().getRelative(BlockFace.DOWN);
        Block current = to.getBlock().getRelative(BlockFace.DOWN);
        if (previous.getX() == current.getX()
                && previous.getY() == current.getY()
                && previous.getZ() == current.getZ()) {
            return;
        }

        PrankBlock entry = plugin.blocks().get(current);
        if (entry == null) {
            return;
        }
        plugin.pranks().trigger(player, current, entry);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.pranks().forget(event.getPlayer());
    }
}
