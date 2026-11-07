package gg.guab.minigames;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/** Cobblestone placed by fighters inside the pit crumbles after a few seconds. */
public final class ArenaDecay implements Listener {

    private final GuabMinigamesPlugin plugin;
    private final BattlePit pit;

    public ArenaDecay(GuabMinigamesPlugin plugin, BattlePit pit) {
        this.plugin = plugin;
        this.pit = pit;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.getConfig().getBoolean("battlepit.cobblestone-decay.enabled", true)) {
            return;
        }
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.COBBLESTONE) {
            return;
        }
        Player player = event.getPlayer();
        if (!pit.inPit(player) || !pit.inArena(block.getLocation())) {
            return;
        }

        int seconds = Math.max(1, plugin.getConfig().getInt("battlepit.cobblestone-decay.seconds", 15));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (block.getType() != Material.COBBLESTONE) {
                return;
            }
            block.setType(Material.AIR, false);
            block.getWorld().playSound(block.getLocation(), Sound.BLOCK_STONE_BREAK, 1.0f, 1.0f);
        }, seconds * 20L);
    }
}
