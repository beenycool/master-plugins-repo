package gg.guab.items;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Locale;

/** Keeps players from breaking the pub's tap, shelves and dartboard. */
public final class PubProtection implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String BYPASS = "guab.pub.protection.bypass";

    private final GuabItemsPlugin plugin;

    public PubProtection(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (!enabled() || event.getPlayer().hasPermission(BYPASS)) {
            return;
        }
        if (!isProtected(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendActionBar(LEGACY.deserialize(message()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (enabled()) {
            event.blockList().removeIf(this::isProtected);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (enabled()) {
            event.blockList().removeIf(this::isProtected);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBurn(BlockBurnEvent event) {
        if (enabled() && isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.pub().forget(event.getPlayer().getUniqueId());
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("pub.protection.enabled", true);
    }

    private String message() {
        return plugin.getConfig().getString("pub.protection.message",
                "&cThat's part of the pub. Ask an admin.");
    }

    private boolean isProtected(Block block) {
        if (block == null || block.getWorld() == null) {
            return false;
        }
        String world = block.getWorld().getName();
        if (sameBlock(block, world, "beer.tap") || sameBlock(block, world, "pub.darts.board")) {
            return true;
        }
        List<String> shelves = plugin.getConfig().getStringList("pub.menu.shelves");
        for (String raw : shelves) {
            String[] parts = raw.split(";");
            if (parts.length != 4 || !parts[0].trim().equalsIgnoreCase(world)) {
                continue;
            }
            try {
                if (block.getX() == Integer.parseInt(parts[1].trim())
                        && block.getY() == Integer.parseInt(parts[2].trim())
                        && block.getZ() == Integer.parseInt(parts[3].trim())) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Malformed shelf entry; skip it.
            }
        }
        return false;
    }

    private boolean sameBlock(Block block, String world, String path) {
        String configuredWorld = plugin.getConfig().getString(path + ".world");
        if (configuredWorld == null || !configuredWorld.toLowerCase(Locale.ROOT)
                .equals(world.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return block.getX() == plugin.getConfig().getInt(path + ".x")
                && block.getY() == plugin.getConfig().getInt(path + ".y")
                && block.getZ() == plugin.getConfig().getInt(path + ".z");
    }
}
