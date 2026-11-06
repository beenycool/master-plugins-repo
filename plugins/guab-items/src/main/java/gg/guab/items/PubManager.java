package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pints, the drunk meter and the pub leaderboard. Lifetime pints are persisted to
 * {@code pints.yml}; the drunk meter only looks at pints drunk in the last window.
 */
public final class PubManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final GuabItemsPlugin plugin;
    private final Map<UUID, PlayerPints> players = new HashMap<>();
    private final File file;
    private final NamespacedKey hologramKey;
    private TextDisplay hologram;
    private BukkitTask task;
    private BukkitTask saveTask;
    private boolean dirty;

    public PubManager(GuabItemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pints.yml");
        this.hologramKey = new NamespacedKey(plugin, "pints_leaderboard");
    }

    public void start() {
        load();
        spawnHologram();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, 20L * 60, 20L * 60);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        saveIfDirty();
        removeHologram();
    }

    public int pints(UUID player) {
        PlayerPints entry = players.get(player);
        return entry == null ? 0 : entry.total;
    }

    /** Number of players with a recorded pint. */
    public int pintsCount() {
        return players.size();
    }

    public int drunkLevel(UUID player) {
        PlayerPints entry = players.get(player);
        if (entry == null) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - windowMillis();
        while (!entry.recent.isEmpty() && entry.recent.peekFirst() < cutoff) {
            entry.recent.pollFirst();
        }
        return entry.recent.size();
    }

    public void recordDrink(Player player) {
        PlayerPints entry = players.computeIfAbsent(player.getUniqueId(),
                id -> new PlayerPints(player.getName(), 0, new ArrayDeque<>()));
        entry.name = player.getName();
        entry.total++;
        entry.recent.addLast(System.currentTimeMillis());
        dirty = true;

        int level = drunkLevel(player.getUniqueId());
        applyDrunkEffects(player, level);
        updateHologram();
    }

    private void applyDrunkEffects(Player player, int level) {
        if (level <= 0) {
            return;
        }
        if (level <= 2) {
            effect(player, PotionEffectType.NAUSEA, 5, 0);
        } else if (level <= 4) {
            effect(player, PotionEffectType.NAUSEA, 7, 0);
            effect(player, PotionEffectType.SLOWNESS, 5, 0);
        } else if (level <= 6) {
            effect(player, PotionEffectType.NAUSEA, 9, 1);
            effect(player, PotionEffectType.SLOWNESS, 6, 0);
        } else {
            effect(player, PotionEffectType.NAUSEA, 12, 1);
            effect(player, PotionEffectType.SLOWNESS, 8, 1);
            effect(player, PotionEffectType.BLINDNESS, 2, 0);
        }
    }

    private void effect(Player player, PotionEffectType type, int seconds, int amplifier) {
        player.addPotionEffect(new PotionEffect(type, seconds * 20, amplifier, false, true, true));
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            int level = drunkLevel(player.getUniqueId());
            if (level <= 0) {
                continue;
            }
            player.sendActionBar(Component.text("Pints ", NamedTextColor.GOLD)
                    .append(Component.text(level, NamedTextColor.WHITE))
                    .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(mood(level), moodColour(level))));
        }
        if (dirty) {
            updateHologram();
            dirty = false;
        }
    }

    private String mood(int level) {
        if (level <= 2) {
            return "Tipsy";
        }
        if (level <= 4) {
            return "Merry";
        }
        if (level <= 6) {
            return "Drunk";
        }
        return "Absolutely Wetherspoons";
    }

    private NamedTextColor moodColour(int level) {
        if (level <= 2) {
            return NamedTextColor.YELLOW;
        }
        if (level <= 4) {
            return NamedTextColor.GOLD;
        }
        if (level <= 6) {
            return NamedTextColor.RED;
        }
        return NamedTextColor.DARK_RED;
    }

    public List<Map.Entry<String, Integer>> top(int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (PlayerPints entry : players.values()) {
            entries.add(Map.entry(entry.name, entry.total));
        }
        entries.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
        return entries.subList(0, Math.min(limit, entries.size()));
    }

    private void spawnHologram() {
        if (!plugin.getConfig().getBoolean("pub.pints.leaderboard.enabled", true)) {
            return;
        }
        Location location = hologramLocation();
        if (location == null) {
            return;
        }
        removeHologram();
        World world = location.getWorld();
        world.getChunkAt(location).load();
        for (var entity : world.getNearbyEntities(location, 6, 6, 6)) {
            if (entity instanceof TextDisplay display
                    && display.getPersistentDataContainer().has(hologramKey, PersistentDataType.BYTE)) {
                entity.remove();
            }
        }
        hologram = world.spawn(location, TextDisplay.class, display -> {
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(false);
            display.setShadowed(true);
            display.setPersistent(false);
            display.setViewRange(0.8f);
            display.getPersistentDataContainer().set(hologramKey, PersistentDataType.BYTE, (byte) 1);
        });
        updateHologram();
    }

    private void removeHologram() {
        if (hologram != null && hologram.isValid()) {
            hologram.remove();
        }
        hologram = null;
    }

    private void updateHologram() {
        if (hologram == null || !hologram.isValid()) {
            return;
        }
        StringBuilder text = new StringBuilder("&6&lPINTS DRUNK");
        List<Map.Entry<String, Integer>> top = top(3);
        if (top.isEmpty()) {
            text.append("\n&7No pints yet");
        } else {
            String[] colours = {"&e", "&f", "&7"};
            for (int i = 0; i < top.size(); i++) {
                text.append('\n').append(colours[i])
                        .append("#").append(i + 1).append(' ')
                        .append(top.get(i).getKey()).append(" &8- &6")
                        .append(top.get(i).getValue());
            }
        }
        hologram.text(LEGACY.deserialize(text.toString()));
    }

    private Location hologramLocation() {
        String world = plugin.getConfig().getString("pub.pints.leaderboard.world", "guab_casino");
        World worldInstance = Bukkit.getWorld(world);
        if (worldInstance == null) {
            return null;
        }
        return new Location(worldInstance,
                plugin.getConfig().getDouble("pub.pints.leaderboard.x", -17.5),
                plugin.getConfig().getDouble("pub.pints.leaderboard.y", 67.3),
                plugin.getConfig().getDouble("pub.pints.leaderboard.z", -12.5));
    }

    public Location centre() {
        String world = plugin.getConfig().getString("pub.centre.world", "guab_casino");
        World worldInstance = Bukkit.getWorld(world);
        if (worldInstance == null) {
            return null;
        }
        return new Location(worldInstance,
                plugin.getConfig().getDouble("pub.centre.x", -20.5),
                plugin.getConfig().getDouble("pub.centre.y", 65.0),
                plugin.getConfig().getDouble("pub.centre.z", -13.0));
    }

    private long windowMillis() {
        return Math.max(30, plugin.getConfig().getInt("pub.drunk.window-seconds", 600)) * 1000L;
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) {
                    continue;
                }
                players.put(id, new PlayerPints(
                        entry.getString("name", "unknown"),
                        entry.getInt("pints", 0),
                        new ArrayDeque<>()));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed UUIDs rather than failing startup.
            }
        }
    }

    private void saveIfDirty() {
        if (!dirty) {
            return;
        }
        save();
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerPints> entry : players.entrySet()) {
            String path = "players." + entry.getKey();
            yaml.set(path + ".name", entry.getValue().name);
            yaml.set(path + ".pints", entry.getValue().total);
        }
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save pints.yml: " + exception.getMessage());
        }
    }

    private static final class PlayerPints {
        private String name;
        private int total;
        private final Deque<Long> recent;

        private PlayerPints(String name, int total, Deque<Long> recent) {
            this.name = name;
            this.total = total;
            this.recent = recent;
        }
    }
}
