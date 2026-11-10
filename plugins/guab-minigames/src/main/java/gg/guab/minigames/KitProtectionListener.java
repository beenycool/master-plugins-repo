package gg.guab.minigames;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import gg.guab.core.HudChannel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * Stops fighters from dropping or placing the tagged battle-kit items, which
 * would otherwise leak arena gear into the world (or duplicate it via hoppers).
 */
public final class KitProtectionListener implements Listener {

    private final GuabMinigamesPlugin plugin;

    public KitProtectionListener(GuabMinigamesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getConfig().getBoolean("kit.prevent-drop", true)) {
            return;
        }
        if (BattleKit.isKitItem(plugin, event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            plugin.actionBar(event.getPlayer(), Component.text("Kit items cannot be dropped.",
                    NamedTextColor.RED), HudChannel.PRIORITY_HINT, 40);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.getConfig().getBoolean("kit.prevent-place", true)) {
            return;
        }
        // Cobblestone is part of the arena strategy and is cleaned up by ArenaDecay.
        if (event.getItemInHand().getType() == org.bukkit.Material.COBBLESTONE) {
            return;
        }
        if (BattleKit.isKitItem(plugin, event.getItemInHand())) {
            event.setCancelled(true);
            plugin.actionBar(event.getPlayer(), Component.text("Kit blocks cannot be placed.",
                    NamedTextColor.RED), HudChannel.PRIORITY_HINT, 40);
        }
    }
}
