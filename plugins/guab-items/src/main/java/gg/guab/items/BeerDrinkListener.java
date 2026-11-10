package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Finished-drink handling for milk, which sobers the drinker up.
 */
public final class BeerDrinkListener implements Listener {

    private final GuabItemsPlugin plugin;

    public BeerDrinkListener(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (!plugin.items().isMilk(item)) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("guab.pub.use")) {
            event.setCancelled(true);
            return;
        }
        plugin.pub().soberUp(player);
        player.sendMessage(Component.text("The milk sobers you up.", NamedTextColor.WHITE));
    }
}
