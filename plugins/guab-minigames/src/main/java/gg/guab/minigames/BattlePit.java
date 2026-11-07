package gg.guab.minigames;

import gg.guab.core.PointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A free-for-all pit outside the parkour. Kills pay points, deaths cost points, and
 * the pit respawns you at its own spawn instead of the world spawn.
 */
public final class BattlePit implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final GuabMinigamesPlugin plugin;
    private final Set<UUID> fighters = new LinkedHashSet<>();
    private final Map<UUID, Integer> streaks = new HashMap<>();
    private final Map<UUID, SavedInventory> savedInventories = new HashMap<>();
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private BukkitTask task;

    public BattlePit(GuabMinigamesPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : new ArrayList<>(fighters)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                restore(player);
            }
        }
        fighters.clear();
        streaks.clear();
        savedInventories.clear();
        protectedUntil.clear();
    }

    public boolean inPit(Player player) {
        return fighters.contains(player.getUniqueId());
    }

    public int fighterCount() {
        return fighters.size();
    }

    /** True when the location is inside the pit's horizontal arena bounds. */
    public boolean inArena(Location location) {
        Location spawn = spawn();
        if (spawn == null || location.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().equals(spawn.getWorld())) {
            return false;
        }
        double radius = plugin.getConfig().getDouble("battlepit.arena-radius", 24.0);
        double dx = location.getX() + 0.5 - spawn.getX();
        double dz = location.getZ() + 0.5 - spawn.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }

    public List<String> fighterNames() {
        List<String> names = new ArrayList<>();
        for (UUID id : fighters) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                names.add(online.getName());
            }
        }
        return names;
    }

    public boolean join(Player player) {
        if (!plugin.getConfig().getBoolean("battlepit.enabled", true)) {
            msg(player, "&cThe battle pit is closed.", NamedTextColor.RED);
            return false;
        }
        Location spawn = spawn();
        if (spawn == null) {
            msg(player, "&cThe battle pit spawn is not set. An admin can run &f/battlepit setspawn&c.", NamedTextColor.RED);
            return false;
        }
        if (!fighters.add(player.getUniqueId())) {
            msg(player, "&eYou are already in the battle pit.", NamedTextColor.YELLOW);
            return false;
        }
        streaks.put(player.getUniqueId(), 0);
        savedInventories.putIfAbsent(player.getUniqueId(), capture(player));
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
        BattleKit.give(player);
        player.teleport(spawn);
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.setFoodLevel(20);
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            player.setHealth(maxHealth.getValue());
        }
        protect(player);
        msg(player, "&aWelcome to the battle pit. Kills pay &6"
                + plugin.getConfig().getInt("battlepit.kill-reward", 50)
                + " points&a, deaths cost &6"
                + plugin.getConfig().getInt("battlepit.death-penalty", 25)
                + "&a. Use &f/kit &ato refresh your gear, leave with &f/battlepit leave&a.",
                NamedTextColor.GRAY);
        broadcast("&e" + player.getName() + " &7entered the pit.", NamedTextColor.GRAY);
        return true;
    }

    public boolean leave(Player player) {
        if (!fighters.remove(player.getUniqueId())) {
            msg(player, "&eYou are not in the battle pit.", NamedTextColor.YELLOW);
            return false;
        }
        streaks.remove(player.getUniqueId());
        protectedUntil.remove(player.getUniqueId());
        restore(player);
        Location exit = exit();
        if (exit != null) {
            player.teleport(exit);
            player.setFallDistance(0);
        }
        msg(player, "&7You left the battle pit.", NamedTextColor.GRAY);
        broadcast("&7" + player.getName() + " left the pit.", NamedTextColor.DARK_GRAY);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID victimId = victim.getUniqueId();
        if (!fighters.contains(victimId)) {
            return;
        }
        protectedUntil.remove(victimId);
        boolean keepInventory = plugin.getConfig().getBoolean("battlepit.keep-inventory", true);
        if (keepInventory) {
            event.setKeepInventory(true);
            event.getDrops().clear();
        }
        event.setDroppedExp(0);

        PointsService points = points();
        int penalty = Math.max(0, plugin.getConfig().getInt("battlepit.death-penalty", 25));
        if (points != null && penalty > 0) {
            points.take(victimId, penalty);
        }
        int lostStreak = streaks.getOrDefault(victimId, 0);
        streaks.put(victimId, 0);

        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victimId) && fighters.contains(killer.getUniqueId())) {
            int reward = Math.max(0, plugin.getConfig().getInt("battlepit.kill-reward", 50));
            if (points != null && reward > 0) {
                points.add(killer.getUniqueId(), reward);
            }
            int streak = streaks.merge(killer.getUniqueId(), 1, Integer::sum);
            broadcast("&6" + killer.getName() + " &7eliminated &f" + victim.getName()
                    + " &7(+&6" + reward + " &7points, streak &e" + streak + "&7).", NamedTextColor.GRAY);
            if (streak > 0 && streak % plugin.getConfig().getInt("battlepit.streak-announce-every", 5) == 0) {
                Bukkit.broadcast(Component.text("[BattlePit] ", NamedTextColor.GOLD)
                        .append(Component.text(killer.getName() + " is on a " + streak + " kill streak!",
                                NamedTextColor.YELLOW)));
            }
        } else {
            broadcast("&7" + victim.getName() + " died in the pit"
                    + (lostStreak > 0 ? " and lost a " + lostStreak + " kill streak" : "") + ".",
                    NamedTextColor.GRAY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!fighters.contains(player.getUniqueId())) {
            return;
        }
        Location spawn = spawn();
        if (spawn == null) {
            return;
        }
        event.setRespawnLocation(spawn);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && fighters.contains(player.getUniqueId())) {
                player.teleport(spawn);
                player.setFallDistance(0);
                BattleKit.give(player);
                protect(player);
            }
        }, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (fighters.remove(id)) {
            restore(event.getPlayer());
            broadcast("&7" + event.getPlayer().getName() + " left the pit.", NamedTextColor.DARK_GRAY);
        }
        streaks.remove(id);
        protectedUntil.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !fighters.contains(victim.getUniqueId())) {
            return;
        }
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        if (isProtected(victim.getUniqueId())) {
            event.setCancelled(true);
            msg(attacker, "&e" + victim.getName() + " &7is spawn protected.", NamedTextColor.GRAY);
            victim.sendActionBar(Component.text("Spawn protection blocked a hit", NamedTextColor.AQUA));
            return;
        }
        if (isProtected(attacker.getUniqueId())) {
            protectedUntil.remove(attacker.getUniqueId());
            msg(attacker, "&7Your spawn protection ended because you attacked.", NamedTextColor.GRAY);
        }
    }

    private Player attacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private void protect(Player player) {
        if (!plugin.getConfig().getBoolean("battlepit.spawn-protection.enabled", true)) {
            return;
        }
        int seconds = Math.max(1, plugin.getConfig().getInt("battlepit.spawn-protection.seconds", 5));
        protectedUntil.put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
        player.sendMessage(Component.text("[BattlePit] ", NamedTextColor.GOLD)
                .append(Component.text("Spawn protection for " + seconds + "s.", NamedTextColor.AQUA)));
    }

    private boolean isProtected(UUID id) {
        Long until = protectedUntil.get(id);
        return until != null && until > System.currentTimeMillis();
    }

    private int protectionSeconds(UUID id) {
        Long until = protectedUntil.get(id);
        if (until == null) {
            return 0;
        }
        long left = until - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    private void tick() {
        if (fighters.isEmpty()) {
            return;
        }
        boolean actionBar = plugin.getConfig().getBoolean("battlepit.action-bar", true);
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(fighters)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (plugin.getConfig().getBoolean("battlepit.auto-leave-outside", true)
                    && !inArena(player.getLocation())) {
                leave(player);
                continue;
            }
            Long until = protectedUntil.get(id);
            if (until != null && until <= now) {
                protectedUntil.remove(id);
                player.sendMessage(Component.text("[BattlePit] ", NamedTextColor.GOLD)
                        .append(Component.text("Spawn protection ended.", NamedTextColor.GRAY)));
            }
            if (!actionBar) {
                continue;
            }
            int streak = streaks.getOrDefault(id, 0);
            Component bar = Component.text("Battle Pit", NamedTextColor.GOLD)
                    .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(fighters.size() + " fighters", NamedTextColor.GRAY))
                    .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Streak " + streak, NamedTextColor.YELLOW))
                    .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("/battlepit leave", NamedTextColor.DARK_GRAY));
            int shield = protectionSeconds(id);
            if (shield > 0) {
                bar = bar.append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Shield " + shield + "s", NamedTextColor.AQUA));
            }
            player.sendActionBar(bar);
        }
    }

    public Location spawn() {
        return location("battlepit.spawn");
    }

    public void setSpawn(Location location) {
        writeLocation("battlepit.spawn", location);
    }

    public Location exit() {
        Location configured = location("battlepit.exit");
        return configured != null ? configured : Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    public void setExit(Location location) {
        writeLocation("battlepit.exit", location);
    }

    private Location location(String path) {
        String worldName = plugin.getConfig().getString(path + ".world");
        if (worldName == null) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world,
                plugin.getConfig().getDouble(path + ".x"),
                plugin.getConfig().getDouble(path + ".y"),
                plugin.getConfig().getDouble(path + ".z"),
                (float) plugin.getConfig().getDouble(path + ".yaw"),
                (float) plugin.getConfig().getDouble(path + ".pitch"));
    }

    private void writeLocation(String path, Location location) {
        plugin.getConfig().set(path + ".world", location.getWorld().getName());
        plugin.getConfig().set(path + ".x", location.getX());
        plugin.getConfig().set(path + ".y", location.getY());
        plugin.getConfig().set(path + ".z", location.getZ());
        plugin.getConfig().set(path + ".yaw", location.getYaw());
        plugin.getConfig().set(path + ".pitch", location.getPitch());
        plugin.saveConfig();
    }

    private PointsService points() {
        var registration = Bukkit.getServicesManager().getRegistration(PointsService.class);
        return registration == null ? null : registration.getProvider();
    }

    private void broadcast(String legacy, NamedTextColor fallback) {
        Component message = Component.text("[BattlePit] ", NamedTextColor.GOLD)
                .append(LEGACY.deserialize(legacy));
        for (UUID id : fighters) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    private void msg(Player player, String legacy, NamedTextColor fallback) {
        player.sendMessage(Component.text("[BattlePit] ", NamedTextColor.GOLD)
                .append(LEGACY.deserialize(legacy)));
    }

    public String formatLocation(Location location) {
        if (location == null) {
            return "not set";
        }
        return location.getWorld().getName() + " " + String.format(Locale.ROOT, "%.1f, %.1f, %.1f",
                location.getX(), location.getY(), location.getZ());
    }
    private SavedInventory capture(Player player) {
        PlayerInventory inventory = player.getInventory();
        return new SavedInventory(
                clone(inventory.getContents()),
                clone(inventory.getArmorContents()),
                inventory.getItemInOffHand() == null ? null : inventory.getItemInOffHand().clone(),
                player.getItemOnCursor() == null ? null : player.getItemOnCursor().clone(),
                inventory.getHeldItemSlot(),
                player.getLevel(),
                player.getExp());
    }

    private void restore(Player player) {
        SavedInventory saved = savedInventories.remove(player.getUniqueId());
        if (saved == null) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setContents(saved.contents());
        inventory.setArmorContents(saved.armor());
        inventory.setItemInOffHand(saved.offHand());
        inventory.setHeldItemSlot(saved.heldSlot());
        player.setItemOnCursor(saved.cursor());
        player.setLevel(saved.level());
        player.setExp(saved.exp());
    }

    private static ItemStack[] clone(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) {
            copy[index] = items[index] == null ? null : items[index].clone();
        }
        return copy;
    }

    private record SavedInventory(ItemStack[] contents, ItemStack[] armor, ItemStack offHand,
                                  ItemStack cursor, int heldSlot, int level, float exp) {
    }

}
