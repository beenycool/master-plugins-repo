package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the beer menu shelves stocked. Right-click opens the 26.3 beer menu;
 * left-click still refuses to let anyone take the display bottles.
 *
 * <p>The bottles are small persistent {@link ItemDisplay} entities tagged in
 * their PDC. They are cosmetic only: the shelf blocks stay protected and the
 * right-click menu is unchanged. Displays are rebuilt on startup/reload so a
 * config edit (or a crashed shutdown) cannot leave duplicates behind.</p>
 */
public final class MenuShelves implements Listener {

    private static final String DISPLAY_TAG = "menu_shelf_display";
    private static final int BOTTLES_PER_SHELF = 3;

    private final GuabItemsPlugin plugin;
    private final NamespacedKey displayKey;
    private final Set<String> locked = new LinkedHashSet<>();

    public MenuShelves(GuabItemsPlugin plugin) {
        this.plugin = plugin;
        this.displayKey = new NamespacedKey(plugin, DISPLAY_TAG);
        reload();
    }

    public void reload() {
        locked.clear();
        for (String raw : plugin.getConfig().getStringList("pub.menu.shelves")) {
            String key = raw.trim();
            if (!key.isEmpty()) {
                locked.add(key);
            }
        }
        restock();
    }

    /** Remove the old tagged displays and, unless disabled, rebuild them. */
    private void restock() {
        removeStaleDisplays();
        if (!plugin.getConfig().getBoolean("pub.menu.shelf-displays", true)) {
            return;
        }
        List<BeerBrand> brands = plugin.beer().allBrands();
        if (brands.isEmpty()) {
            return;
        }

        int index = 0;
        for (String raw : locked) {
            Shelf shelf = Shelf.parse(raw);
            if (shelf == null) {
                continue;
            }
            World world = Bukkit.getWorld(shelf.world());
            if (world == null || !world.isChunkLoaded(shelf.x() >> 4, shelf.z() >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(shelf.x(), shelf.y(), shelf.z());
            if (block.getType() == Material.AIR) {
                // The shelf has not been built in this world yet.
                continue;
            }
            double surface = block.getBoundingBox().getMaxY() + 0.02;
            for (int bottle = 0; bottle < BOTTLES_PER_SHELF; bottle++) {
                double offset = (bottle + 0.5) / BOTTLES_PER_SHELF;
                Location location = new Location(
                        world,
                        shelf.x() + offset,
                        surface,
                        shelf.z() + 0.5,
                        180.0f,
                        0.0f);
                BeerBrand brand = brands.get(index++ % brands.size());
                spawnBottle(location, brand);
            }
        }
    }

    private void spawnBottle(Location location, BeerBrand brand) {
        ItemStack bottle = plugin.items().createBeer(brand, 1);
        try {
            location.getWorld().spawn(location, ItemDisplay.class, display -> {
                display.setItemStack(bottle);
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                display.setBillboard(Display.Billboard.FIXED);
                display.setPersistent(true);
                display.setInvulnerable(true);
                display.setGravity(false);
                display.setSilent(true);
                display.setBrightness(new Display.Brightness(15, 15));
                display.setViewRange(1.0f);
                display.setRotation(180.0f, 0.0f);
                display.setTransformation(new Transformation(
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        new AxisAngle4f(),
                        new Vector3f(0.45f, 0.45f, 0.45f),
                        new AxisAngle4f()));
                display.getPersistentDataContainer()
                        .set(displayKey, PersistentDataType.BYTE, (byte) 1);
            });
        } catch (RuntimeException error) {
            plugin.getLogger().fine("Could not place menu shelf bottle at "
                    + location + ": " + error.getMessage());
        }
    }

    /** Delete every display this manager has ever spawned, across all worlds. */
    private void removeStaleDisplays() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (display.getPersistentDataContainer()
                        .has(displayKey, PersistentDataType.BYTE)) {
                    display.remove();
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
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
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
            plugin.beerMenu().open(event.getPlayer());
            return;
        }
        event.getPlayer().sendMessage(Component.text("[Spoons] ", NamedTextColor.GOLD)
                .append(Component.text("The menu is for looking at. Right-click for the full list.",
                        NamedTextColor.GRAY)));
    }

    /** One configured shelf coordinate, parsed from ``world;x;y;z``. */
    private record Shelf(String world, int x, int y, int z) {

        private static Shelf parse(String raw) {
            String[] parts = raw.split(";");
            if (parts.length != 4) {
                return null;
            }
            try {
                return new Shelf(
                        parts[0].trim(),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim()),
                        Integer.parseInt(parts[3].trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
