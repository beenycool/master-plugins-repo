package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the beer menu shelves stocked by cancelling any attempt to take a bottle.
 */
public final class MenuShelves implements Listener {

    private final GuabItemsPlugin plugin;
    private final Set<String> locked = new HashSet<>();

    public MenuShelves(GuabItemsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        locked.clear();
        for (String raw : plugin.getConfig().getStringList("pub.menu.shelves")) {
            locked.add(raw.trim());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        String key = block.getWorld().getName() + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ();
        if (!locked.contains(key)) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("[Spoons] ", NamedTextColor.GOLD)
                .append(Component.text("The menu is for looking at. Order at the bar.", NamedTextColor.GRAY)));
    }
}
