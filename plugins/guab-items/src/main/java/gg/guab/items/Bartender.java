package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A stationary bartender behind the Wetherspoons bar. Right-clicking him opens
 * the beer menu; he cannot be hurt, pushed or set on fire.
 */
public final class Bartender implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String KEY = "pub_bartender";

    private final GuabItemsPlugin plugin;
    private final NamespacedKey bartenderKey;

    public Bartender(GuabItemsPlugin plugin) {
        this.plugin = plugin;
        this.bartenderKey = new NamespacedKey(plugin, KEY);
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        reload();
    }

    public void reload() {
        removeAll();
        if (!plugin.getConfig().getBoolean("pub.bartender.enabled", true)) {
            return;
        }
        String worldName = plugin.getConfig().getString("pub.bartender.world", "guab_casino");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("Bartender world is not loaded: " + worldName);
            return;
        }
        Location location = new Location(world,
                plugin.getConfig().getDouble("pub.bartender.x", -17.5),
                plugin.getConfig().getDouble("pub.bartender.y", 65.0),
                plugin.getConfig().getDouble("pub.bartender.z", -15.5),
                (float) plugin.getConfig().getDouble("pub.bartender.yaw", 0.0),
                (float) plugin.getConfig().getDouble("pub.bartender.pitch", 0.0));
        String name = plugin.getConfig().getString("pub.bartender.name", "&6Wetherspoons Bartender");
        world.spawn(location, Villager.class, villager -> {
            villager.customName(LEGACY.deserialize(name));
            villager.setCustomNameVisible(true);
            villager.setAI(false);
            villager.setInvulnerable(true);
            villager.setSilent(true);
            villager.setPersistent(true);
            villager.setRemoveWhenFarAway(false);
            villager.setCollidable(false);
            villager.setCanPickupItems(false);
            villager.setProfession(Villager.Profession.NITWIT);
            villager.setVillagerType(Villager.Type.PLAINS);
            villager.getPersistentDataContainer().set(bartenderKey, PersistentDataType.BYTE, (byte) 1);
        });
        plugin.getLogger().info("Bartender spawned at " + worldName + " "
                + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ".");
    }

    public void stop() {
        removeAll();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isBartender(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        GuabSounds.play(player, Sound.ENTITY_VILLAGER_YES, 0.8f, 1.0f);
        List<String> messages = plugin.getConfig().getStringList("pub.bartender.messages");
        if (!messages.isEmpty()) {
            String line = messages.get(ThreadLocalRandom.current().nextInt(messages.size()));
            player.sendMessage(Component.text("[Spoons] ", NamedTextColor.GOLD)
                    .append(LEGACY.deserialize(line)));
        }
        if (plugin.getConfig().getBoolean("pub.bartender.menu", true)) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> plugin.beerMenu().open(player));
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (isBartender(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCombust(EntityCombustEvent event) {
        if (isBartender(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    private boolean isBartender(Entity entity) {
        return entity instanceof Villager
                && entity.getPersistentDataContainer().has(bartenderKey, PersistentDataType.BYTE);
    }

    private void removeAll() {
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (villager.getPersistentDataContainer().has(bartenderKey, PersistentDataType.BYTE)) {
                    villager.remove();
                }
            }
        }
    }
}
