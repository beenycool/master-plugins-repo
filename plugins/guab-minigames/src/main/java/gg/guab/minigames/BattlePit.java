package gg.guab.minigames;

import gg.guab.core.GuabFx;
import gg.guab.core.HudChannel;
import gg.guab.core.PointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
    static final String METADATA_KEY = "guab.battlepit";

    private final GuabMinigamesPlugin plugin;
    private final Set<UUID> fighters = new LinkedHashSet<>();
    private final Map<UUID, Integer> streaks = new HashMap<>();
    private final Map<UUID, SessionStats> sessionStats = new HashMap<>();
    private final Map<UUID, Location> joinLocations = new HashMap<>();
    private final Map<UUID, Double> lastHitHealth = new HashMap<>();
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private final SavedInventoryStore savedInventories;
    private BukkitTask task;

    public BattlePit(GuabMinigamesPlugin plugin) {
        this.plugin = plugin;
        this.savedInventories = new SavedInventoryStore(
                new File(plugin.getDataFolder(), "saved-inventories.yml"), plugin.getLogger());
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
                releaseRespawnHold(player);
                clearMetadata(player);
                restore(player);
            }
        }
        fighters.clear();
        streaks.clear();
        protectedUntil.clear();
        joinLocations.clear();
        lastHitHealth.clear();
    }

    public boolean inPit(Player player) {
        return fighters.contains(player.getUniqueId());
    }

    public int fighterCount() {
        return fighters.size();
    }

    public int streak(Player player) {
        return streaks.getOrDefault(player.getUniqueId(), 0);
    }

    public int topStreak() {
        int top = 0;
        for (int value : streaks.values()) {
            if (value > top) {
                top = value;
            }
        }
        return top;
    }

    /** Live session stats for a player, or {@code null} if they have never fought. */
    public SessionStats stats(UUID id) {
        return sessionStats.get(id);
    }

    public SessionStats stats(Player player) {
        return sessionStats.get(player.getUniqueId());
    }

    /** Case-insensitive lookup of the last session for an offline player. */
    public SessionStats statsByName(String name) {
        for (SessionStats stats : sessionStats.values()) {
            if (stats.name().equalsIgnoreCase(name)) {
                return stats;
            }
        }
        return null;
    }

    /** Session leaderboard, newest values first. */
    public List<SessionStats> leaderboard(String metric, int limit) {
        Comparator<SessionStats> comparator = switch (metric == null ? "" : metric.toLowerCase(Locale.ROOT)) {
            case "deaths" -> Comparator.comparingInt(SessionStats::deaths).reversed();
            case "streak" -> Comparator.comparingInt(SessionStats::bestStreak).reversed();
            default -> Comparator.comparingInt(SessionStats::kills).reversed();
        };
        return sessionStats.values().stream()
                .filter(SessionStats::hasPlayed)
                .sorted(comparator.thenComparing(SessionStats::name, String.CASE_INSENSITIVE_ORDER))
                .limit(Math.max(1, limit))
                .toList();
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
        return withinArena(location.getX() + 0.5, location.getZ() + 0.5,
                spawn.getX(), spawn.getZ(), radius);
    }

    /** Pure arena check, exposed for tests and for callers that only have coordinates. */
    static boolean withinArena(double x, double z, double spawnX, double spawnZ, double radius) {
        if (!Double.isFinite(x) || !Double.isFinite(z)
                || !Double.isFinite(spawnX) || !Double.isFinite(spawnZ)
                || !Double.isFinite(radius) || radius < 0.0) {
            return false;
        }
        double dx = x - spawnX;
        double dz = z - spawnZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    /**
     * A streak announcement interval of zero or less means "never announce".
     * This avoids the modulo-by-zero that used to crash the kill handler.
     */
    static boolean shouldAnnounceStreak(int streak, int every) {
        return streak > 0 && every > 0 && streak % every == 0;
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
        if (!player.hasPermission("guab.battlepit.use")) {
            msg(player, "&cYou need guab.battlepit.use to join the battle pit.", NamedTextColor.RED);
            return false;
        }
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
        UUID id = player.getUniqueId();
        streaks.put(id, 0);
        sessionStats.put(id, new SessionStats(player.getName()));
        protectedUntil.remove(id);
        lastHitHealth.remove(id);
        joinLocations.put(id, player.getLocation().clone());
        if (plugin.kitEnabled()) {
            savedInventories.put(id, capture(player));
            BattleKit.resetAndGive(plugin, player);
        }
        player.teleport(spawn);
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.setFoodLevel(20);
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            player.setHealth(maxHealth.getValue());
        }
        clearMetadata(player);
        player.setMetadata(METADATA_KEY, new FixedMetadataValue(plugin, true));
        if (plugin.kit() != null) {
            plugin.kit().resetCooldown(id);
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
        return leave(player, LeaveReason.MANUAL);
    }

    private boolean leave(Player player, LeaveReason reason) {
        UUID id = player.getUniqueId();
        if (!fighters.remove(id)) {
            if (reason == LeaveReason.MANUAL) {
                msg(player, "&eYou are not in the battle pit.", NamedTextColor.YELLOW);
            }
            return false;
        }
        Location target = reason.teleports() ? exitTarget(id) : null;
        cleanup(player);
        if (plugin.kit() != null) {
            plugin.kit().resetCooldown(id);
        }
        if (target != null) {
            player.teleport(target);
            player.setFallDistance(0);
        }
        switch (reason) {
            case MANUAL -> {
                msg(player, "&7You left the battle pit.", NamedTextColor.GRAY);
                broadcast("&7" + player.getName() + " left the pit.", NamedTextColor.DARK_GRAY);
            }
            case AUTO_OUTSIDE -> {
                msg(player, "&7You left the pit because you moved outside the arena.", NamedTextColor.GRAY);
                broadcast("&7" + player.getName() + " wandered out of the pit.", NamedTextColor.DARK_GRAY);
            }
            case KICK -> {
                msg(player, "&cYou were removed from the battle pit.", NamedTextColor.RED);
                broadcast("&7" + player.getName() + " was removed from the pit.", NamedTextColor.DARK_GRAY);
            }
            case PURGE -> msg(player, "&7The battle pit was purged.", NamedTextColor.GRAY);
        }
        return true;
    }

    /** Removes a fighter. Used by `/battlepit kick`. */
    public boolean kick(Player target) {
        return leave(target, LeaveReason.KICK);
    }

    /** Removes every fighter. Returns how many were removed. */
    public int purge() {
        int removed = 0;
        for (UUID id : new ArrayList<>(fighters)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                if (leave(player, LeaveReason.PURGE)) {
                    removed++;
                }
                continue;
            }
            fighters.remove(id);
            streaks.remove(id);
            protectedUntil.remove(id);
            joinLocations.remove(id);
            lastHitHealth.remove(id);
            removed++;
        }
        return removed;
    }

    private Location exitTarget(UUID id) {
        String mode = plugin.getConfig().getString("battlepit.exit-mode", "configured");
        if ("join".equalsIgnoreCase(mode)) {
            Location joined = joinLocations.get(id);
            if (joined != null && joined.getWorld() != null) {
                return joined;
            }
        }
        return exit();
    }

    private void cleanup(Player player) {
        UUID id = player.getUniqueId();
        streaks.remove(id);
        protectedUntil.remove(id);
        joinLocations.remove(id);
        lastHitHealth.remove(id);
        releaseRespawnHold(player);
        clearMetadata(player);
        restore(player);
    }

    private void clearMetadata(Player player) {
        player.removeMetadata(METADATA_KEY, plugin);
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

        double victimHealth = lastHitHealth.getOrDefault(victimId, 0.0);
        SessionStats victimStats = statsFor(victim);

        PointsService points = points();
        int penalty = Math.max(0, plugin.getConfig().getInt("battlepit.death-penalty", 25));
        int lost = 0;
        if (points != null && penalty > 0) {
            int before = points.get(victimId);
            points.take(victimId, penalty);
            lost = before - points.get(victimId);
            if (lost > 0) {
                msg(victim, "&7You lost &c" + lost + " points&7.", NamedTextColor.GRAY);
            } else {
                msg(victim, "&7You had nothing left to lose.", NamedTextColor.GRAY);
            }
        }
        victimStats.addDeaths(1);
        victimStats.addPoints(-lost);

        int lostStreak = streaks.getOrDefault(victimId, 0);
        victimStats.recordStreak(lostStreak);
        streaks.put(victimId, 0);

        String coords = formatCoordinates(victim.getLocation());
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victimId) && fighters.contains(killer.getUniqueId())) {
            int reward = Math.max(0, plugin.getConfig().getInt("battlepit.kill-reward", 50));
            if (points != null && reward > 0) {
                points.add(killer.getUniqueId(), reward);
            }
            int streak = streaks.merge(killer.getUniqueId(), 1, Integer::sum);
            SessionStats killerStats = statsFor(killer);
            killerStats.addKills(1);
            killerStats.addPoints(reward);
            killerStats.recordStreak(streak);

            int balance = points == null ? 0 : points.get(killer.getUniqueId());
            String weapon = weaponName(killer, victim);
            broadcast("&6" + killer.getName() + " &7[" + weapon + "&7] &7eliminated &f"
                    + victim.getName() + " &7at &f" + coords
                    + " &7with the victim on &c" + String.format(Locale.ROOT, "%.1f", Math.max(0.0, victimHealth))
                    + " HP&7. &a+&6" + reward + " &7(&6" + balance + " pts&7), streak &e" + streak
                    + "&7.", NamedTextColor.GRAY);
            if (plugin.sounds(killer)) {
                killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,
                        SoundCategory.PLAYERS, 0.6f, 1.4f);
            }
            plugin.actionBar(killer, Component.text("Eliminated " + victim.getName()
                    + "  +" + reward + " pts  Streak " + streak, NamedTextColor.GOLD),
                    HudChannel.PRIORITY_CRITICAL, 40);
            String victimLoss = lost > 0 ? "  -" + lost + " pts" : "";
            plugin.actionBar(victim, Component.text("Eliminated by " + killer.getName()
                    + victimLoss, NamedTextColor.RED), HudChannel.PRIORITY_CRITICAL, 40);
            int every = plugin.getConfig().getInt("battlepit.streak-announce-every", 5);
            if (shouldAnnounceStreak(streak, every)) {
                plugin.actionBar(killer, Component.text("STREAK " + streak, NamedTextColor.YELLOW),
                        HudChannel.PRIORITY_CRITICAL, 60);
                Bukkit.broadcast(Component.text("[BattlePit] ", NamedTextColor.GOLD)
                        .append(Component.text(killer.getName() + " is on a " + streak + " kill streak!",
                                NamedTextColor.YELLOW)));
            }
        } else {
            broadcast("&7" + victim.getName() + " died in the pit at &f" + coords
                    + (lostStreak > 0 ? " and lost a " + lostStreak + " kill streak" : "") + ".",
                    NamedTextColor.GRAY);
            plugin.actionBar(victim, Component.text("You died in the pit", NamedTextColor.RED),
                    HudChannel.PRIORITY_CRITICAL, 40);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageTracked(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)
                || !fighters.contains(victim.getUniqueId())) {
            return;
        }
        double remaining = victim.getHealth() - event.getFinalDamage();
        lastHitHealth.put(victim.getUniqueId(), Math.max(0.0, remaining));
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
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !fighters.contains(player.getUniqueId())) {
                return;
            }
            player.teleport(spawn);
            player.setFallDistance(0);
            player.setInvulnerable(true);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 250, false, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 80, 250, false, false, false));
            respawnCountdown(player, 3);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (fighters.contains(id)) {
            return;
        }
        if (!plugin.getConfig().getBoolean("battlepit.restore-saved-on-join", true)) {
            return;
        }
        SavedInventory saved = savedInventories.get(id);
        if (saved == null) {
            return;
        }
        apply(player, saved);
        savedInventories.remove(id);
        msg(player, "&aYour saved battle pit inventory was restored.", NamedTextColor.GRAY);
    }

    private void respawnCountdown(Player player, int seconds) {
        if (!player.isOnline() || !fighters.contains(player.getUniqueId())) {
            releaseRespawnHold(player);
            return;
        }
        if (seconds <= 0) {
            player.setInvulnerable(false);
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            player.removePotionEffect(PotionEffectType.JUMP_BOOST);
            if (plugin.kitEnabled()) {
                BattleKit.resetAndGive(plugin, player);
            }
            protect(player);
            int streak = streaks.getOrDefault(player.getUniqueId(), 0);
            String call = streak > 0 ? "STREAK " + streak : "FIGHT!";
            plugin.actionBar(player, Component.text(call, NamedTextColor.RED),
                    HudChannel.PRIORITY_CRITICAL, 40);
            GuabFx.arrival(player, call,
                    NamedTextColor.RED, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f);
            return;
        }
        player.showTitle(Title.title(
                Component.text(String.valueOf(seconds), NamedTextColor.RED),
                Component.text("RESPAWNING", NamedTextColor.GRAY),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(400), Duration.ofMillis(100))));
        plugin.actionBar(player, Component.text("Respawning in " + seconds + "s", NamedTextColor.RED),
                HudChannel.PRIORITY_CRITICAL, 25);
        if (plugin.sounds(player)) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT,
                    SoundCategory.PLAYERS, 0.7f, 1.2f);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> respawnCountdown(player, seconds - 1), 20L);
    }

    private void releaseRespawnHold(Player player) {
        player.setInvulnerable(false);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.JUMP_BOOST);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (fighters.remove(id)) {
            releaseRespawnHold(player);
            clearMetadata(player);
            restore(player);
            broadcast("&7" + player.getName() + " left the pit.", NamedTextColor.DARK_GRAY);
        }
        streaks.remove(id);
        protectedUntil.remove(id);
        joinLocations.remove(id);
        lastHitHealth.remove(id);
        if (plugin.kit() != null) {
            plugin.kit().forget(id);
        }
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
            plugin.actionBar(victim, Component.text("Spawn protection blocked a hit", NamedTextColor.AQUA),
                    HudChannel.PRIORITY_CRITICAL, 30);
            return;
        }
        if (isProtected(attacker.getUniqueId())) {
            protectedUntil.remove(attacker.getUniqueId());
            msg(attacker, "&7Your spawn protection ended because you attacked.", NamedTextColor.GRAY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowHit(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("battlepit.arrow-hp", true)) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim) || !(event.getDamager() instanceof Arrow arrow)) {
            return;
        }
        if (!(arrow.getShooter() instanceof Player shooter)
                || shooter.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        if (!fighters.contains(shooter.getUniqueId()) || !fighters.contains(victim.getUniqueId())) {
            return;
        }
        double max = 20.0;
        var maxHealth = victim.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            max = maxHealth.getValue();
        }
        double health = Math.max(0.0, victim.getHealth() - event.getFinalDamage());
        NamedTextColor colour = health <= max * 0.25
                ? NamedTextColor.RED
                : health <= max * 0.5 ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
        plugin.actionBar(shooter, Component.text(
                String.format(Locale.ROOT, "Hit %s — %.1f/%.0f HP", victim.getName(), health, max),
                colour), HudChannel.PRIORITY_HINT, 30);
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

    private String weaponName(Player killer, Player victim) {
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Arrow) {
            return "bow";
        }
        return itemName(killer.getInventory().getItemInMainHand());
    }

    private String itemName(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getType() == Material.AIR) {
            return "fists";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        return item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private SessionStats statsFor(Player player) {
        return sessionStats.computeIfAbsent(player.getUniqueId(),
                id -> new SessionStats(player.getName()));
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
                    && !player.isDead() && player.getHealth() > 0.0
                    && !inArena(player.getLocation())) {
                // The player may have been moved by another plugin (e.g. /parkour).
                // Restore their gear but never yank them back to the pit exit.
                leave(player, LeaveReason.AUTO_OUTSIDE);
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
            plugin.actionBar(player, bar, HudChannel.PRIORITY_HINT, 30);
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
        return location.getWorld().getName() + " " + formatCoordinates(location);
    }

    private String formatCoordinates(Location location) {
        return String.format(Locale.ROOT, "%.1f, %.1f, %.1f",
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
        if (saved != null) {
            apply(player, saved);
        }
    }

    private void apply(Player player, SavedInventory saved) {
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

    static final class SessionStats {

        private final String name;
        private int kills;
        private int deaths;
        private int bestStreak;
        private int points;

        SessionStats(String name) {
            this.name = name == null ? "unknown" : name;
        }

        String name() {
            return name;
        }

        int kills() {
            return kills;
        }

        int deaths() {
            return deaths;
        }

        int bestStreak() {
            return bestStreak;
        }

        int points() {
            return points;
        }

        boolean hasPlayed() {
            return kills > 0 || deaths > 0 || points != 0;
        }

        String kd() {
            if (deaths == 0) {
                return kills == 0 ? "0.00" : String.format(Locale.ROOT, "%.2f", (double) kills);
            }
            return String.format(Locale.ROOT, "%.2f", (double) kills / deaths);
        }

        void addKills(int amount) {
            kills += amount;
        }

        void addDeaths(int amount) {
            deaths += amount;
        }

        void addPoints(int amount) {
            points += amount;
        }

        void recordStreak(int streak) {
            if (streak > bestStreak) {
                bestStreak = streak;
            }
        }
    }

    private enum LeaveReason {
        MANUAL(true),
        AUTO_OUTSIDE(false),
        KICK(true),
        PURGE(true);

        private final boolean teleports;

        LeaveReason(boolean teleports) {
            this.teleports = teleports;
        }

        boolean teleports() {
            return teleports;
        }
    }
}
