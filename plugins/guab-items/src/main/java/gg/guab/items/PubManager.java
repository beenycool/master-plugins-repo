package gg.guab.items;

import gg.guab.core.HudChannel;
import gg.guab.core.PointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pints, the weighted drunk meter, hangovers, milestones and the pub
 * leaderboard. The meter is based on real alcohol units from {@link BeerStyle},
 * so a stout hits harder than an alcohol-free pint.
 */
public final class PubManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Map<Integer, Integer> DEFAULT_MILESTONES = Map.of(
            1, 5,
            10, 25,
            25, 100,
            50, 250,
            100, 1000);

    private final GuabItemsPlugin plugin;
    private final Map<UUID, PlayerPints> players = new HashMap<>();
    private final Map<UUID, DrunkEffects.Tier> lastTier = new HashMap<>();
    private final File file;
    private final NamespacedKey hologramKey;
    private TextDisplay hologram;
    private BukkitTask task;
    private BukkitTask saveTask;
    private boolean dirty;
    private boolean hologramDirty;
    private int tickCounter;

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

    public void reload() {
        spawnHologram();
    }

    public int pints(UUID player) {
        PlayerPints entry = players.get(player);
        return entry == null ? 0 : entry.total;
    }

    public int pintsCount() {
        return players.size();
    }

    public int totalPints() {
        int total = 0;
        for (PlayerPints entry : players.values()) {
            total += entry.total;
        }
        return total;
    }

    /** Number of drinks still inside the drunk window. */
    public int drunkLevel(UUID player) {
        PlayerPints entry = players.get(player);
        if (entry == null) {
            return 0;
        }
        prune(entry);
        return entry.recent.size();
    }

    /** Effective alcohol units, linearly metabolised over the window. */
    public double drunkUnits(UUID player) {
        PlayerPints entry = players.get(player);
        if (entry == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long window = windowMillis();
        prune(entry);
        double units = 0;
        for (Drink drink : entry.recent) {
            long age = now - drink.at;
            double remaining = Math.clamp(1.0 - (double) age / window, 0.0, 1.0);
            units += (drink.unitsMilli / 1000.0) * remaining;
        }
        return units;
    }

    public double impairment(UUID player) {
        return Math.clamp(drunkUnits(player) / 10.0, 0.0, 1.0);
    }

    public int tier(double units) {
        return DrunkEffects.forUnits(units).level();
    }

    public String tierLabel(double units) {
        return DrunkEffects.forUnits(units).label();
    }

    private static DrunkEffects.Tier nextTier(DrunkEffects.Tier tier) {
        DrunkEffects.Tier[] tiers = DrunkEffects.Tier.values();
        return tier.ordinal() + 1 < tiers.length ? tiers[tier.ordinal() + 1] : null;
    }

    private static void playTierChime(Player player) {
        GuabSounds.play(player, Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 0.6f, 1.2f);
    }

    /** Drunk meter text shares the meter lane with other passive HUD meters. */
    private void sendMeter(Player player, Component bar) {
        HudChannel channel = plugin.hudChannel();
        if (channel != null) {
            channel.send(player, bar, HudChannel.PRIORITY_METER, 30);
            return;
        }
        if (plugin.preferences() != null
                && !plugin.preferences().actionbar(player.getUniqueId())) {
            return;
        }
        player.sendActionBar(bar);
    }

    private void clearMeter(Player player) {
        sendMeter(player, Component.empty());
    }

    public int rank(UUID player) {
        int pints = pints(player);
        if (pints <= 0) {
            return 0;
        }
        int rank = 1;
        for (PlayerPints entry : players.values()) {
            if (entry.total > pints) {
                rank++;
            }
        }
        return rank;
    }

    public int balance(UUID player) {
        PointsService points = points();
        return points == null ? 0 : points.get(player);
    }

    public void recordDrink(Player player, BeerBrand brand) {
        recordDrink(player, brand.style(), brand.unitsMilli());
    }

    public void recordDrink(Player player, BeerStyle style) {
        recordDrink(player, style, style.unitsMilli());
    }

    private void recordDrink(Player player, BeerStyle style, int unitsMilli) {
        PlayerPints entry = players.computeIfAbsent(player.getUniqueId(),
                id -> new PlayerPints(player.getName()));
        entry.name = player.getName();
        entry.total++;
        entry.unitsMilli += unitsMilli;
        entry.recent.addLast(new Drink(System.currentTimeMillis(), unitsMilli, style.id()));
        prune(entry);
        dirty = true;
        hologramDirty = true;

        double units = drunkUnits(player.getUniqueId());
        entry.peakUnits = Math.max(entry.peakUnits, units);
        DrunkEffects.Tier tier = DrunkEffects.forUnits(units);
        DrunkEffects.Tier previous = lastTier.put(player.getUniqueId(), tier);
        DrunkEffects.apply(player, tier, previous, style.id());
        if (tier.level() > 0 && (previous == null || tier.level() > previous.level())) {
            player.showTitle(Title.title(Component.text(tier.label(), tier.colour()), Component.empty(),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(800), Duration.ofMillis(400))));
            GuabSounds.play(player, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.8f, 1.0f);
            player.sendMessage(prefix().append(Component.text(
                    "You are now " + tier.label() + ".", tier.colour())));
        }
        awardMilestones(player, entry);
        updateHologram();
    }

    /** A fatal pint still counts on the leaderboard, but adds no units. */
    public void recordLethalDrink(Player player) {
        PlayerPints entry = players.computeIfAbsent(player.getUniqueId(),
                id -> new PlayerPints(player.getName()));
        entry.name = player.getName();
        entry.total++;
        dirty = true;
        hologramDirty = true;
        updateHologram();
    }

    /** Milk clears the meter, the drunk effects and any hangover. */
    public void soberUp(Player player) {
        PlayerPints entry = players.get(player.getUniqueId());
        if (entry != null) {
            entry.recent.clear();
            entry.peakUnits = 0;
            entry.hangoverUntil = 0;
            dirty = true;
            hologramDirty = true;
        }
        DrunkEffects.clear(player);
        lastTier.remove(player.getUniqueId());
        clearMeter(player);
    }

    public void recordDrink(Player player) {
        recordDrink(player, plugin.beer().houseBrand());
    }

    /** Drops per-player effect bookkeeping when they disconnect. */
    public void forget(UUID player) {
        lastTier.remove(player);
        DrunkEffects.forget(player);
    }

    private String latestStyle(PlayerPints entry) {
        if (entry == null) {
            return null;
        }
        var drinks = entry.recent.descendingIterator();
        while (drinks.hasNext()) {
            Drink drink = drinks.next();
            if (drink.unitsMilli() > 0) {
                return drink.styleId();
            }
        }
        return null;
    }

    private void prune(PlayerPints entry) {
        long cutoff = System.currentTimeMillis() - windowMillis();
        while (!entry.recent.isEmpty() && entry.recent.peekFirst().at < cutoff) {
            entry.recent.pollFirst();
        }
    }

    private void tick() {
        tickCounter++;
        if ((hologram == null || !hologram.isValid()) && tickCounter % 10 == 0
                && plugin.getConfig().getBoolean("pub.pints.leaderboard.enabled", true)) {
            // GuabItems can enable before GuabGambling has loaded guab_casino.
            spawnHologram();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            double units = drunkUnits(id);
            DrunkEffects.Tier tier = DrunkEffects.forUnits(units);
            DrunkEffects.Tier previous = lastTier.get(id);
            PlayerPints profile = players.get(id);
            String latestStyle = latestStyle(profile);

            if (units <= 0.05) {
                if (previous != null) {
                    DrunkEffects.clear(player);
                    if (previous.level() >= 2) {
                        applyHangover(player);
                    }
                    lastTier.remove(id);
                    if (previous.level() > 0) {
                        playTierChime(player);
                    }
                    clearMeter(player);
                }
                continue;
            }

            if (previous == null || previous != tier || tickCounter % 5 == 0) {
                DrunkEffects.apply(player, tier, previous, latestStyle);
            }
            if (previous == null || previous != tier) {
                lastTier.put(id, tier);
            }
            if (previous != null && tier.level() < previous.level()) {
                playTierChime(player);
            }
            if (plugin.darts() == null || !plugin.darts().isPlaying(id)) {
                Component bar = Component.text("Pints ", NamedTextColor.GOLD)
                        .append(Component.text(pints(id), NamedTextColor.WHITE))
                        .append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(String.format(Locale.ROOT, "%.1f", units) + " units",
                                NamedTextColor.AQUA))
                        .append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(tier.label(), tier.colour()));
                DrunkEffects.Tier next = nextTier(tier);
                if (next != null) {
                    bar = bar.append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(String.format(Locale.ROOT, "%.1f", next.minimumUnits())
                                    + " to " + next.label(), NamedTextColor.GRAY));
                }
                sendMeter(player, bar);
            }
            if (tier.level() >= 3 && ThreadLocalRandom.current().nextInt(100) < 8) {
                player.getWorld().spawnParticle(Particle.NOTE,
                        player.getLocation().add(0, 2.2, 0), 1, 0.35, 0.4, 0.35, 0.0);
            }
            if (tier.level() >= 4 && ThreadLocalRandom.current().nextInt(100) < 5) {
                player.getWorld().spawnParticle(Particle.SMOKE,
                        player.getLocation().add(0.5, 1.0, 0.5), 2, 0.2, 0.2, 0.2, 0.01);
            }
            if (tier.level() >= 4 && ThreadLocalRandom.current().nextInt(100) < 5) {
                player.getWorld().spawnParticle(Particle.WITCH,
                        player.getLocation().add(0, 1.2, 0), 3, 0.4, 0.5, 0.4, 0.0);
            }
            if (tier.level() >= 5 && ThreadLocalRandom.current().nextInt(100) < 4) {
                player.getWorld().spawnParticle(Particle.SOUL,
                        player.getLocation().add(0, 0.4, 0), 1, 0.3, 0.1, 0.3, 0.0);
            }
        }
        if (hologramDirty) {
            updateHologram();
            hologramDirty = false;
        }
    }

    private void applyHangover(Player player) {
        PlayerPints entry = players.get(player.getUniqueId());
        if (entry == null || entry.peakUnits < 5.0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < entry.hangoverUntil) {
            return;
        }
        entry.hangoverUntil = now + 90_000L;
        entry.peakUnits = 0;
        DrunkEffects.applyHangover(player);
        player.sendMessage(prefix().append(Component.text(
                "Hangover. The carpet is louder than it looks.", NamedTextColor.DARK_RED)));
    }

    public List<Map.Entry<String, Integer>> top(int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (PlayerPints entry : players.values()) {
            entries.add(Map.entry(entry.name, entry.total));
        }
        entries.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
        return entries.subList(0, Math.min(limit, entries.size()));
    }

    public List<Map.Entry<String, Integer>> topUnits(int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (PlayerPints entry : players.values()) {
            entries.add(Map.entry(entry.name, (int) Math.round(entry.unitsMilli / 1000.0)));
        }
        entries.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
        return entries.subList(0, Math.min(limit, entries.size()));
    }

    private void awardMilestones(Player player, PlayerPints entry) {
        for (Map.Entry<Integer, Integer> milestone : milestones().entrySet()) {
            int threshold = milestone.getKey();
            if (entry.total >= threshold && entry.milestones.add(threshold)) {
                int reward = milestone.getValue();
                PointsService points = points();
                if (points != null && reward > 0) {
                    points.add(player.getUniqueId(), reward);
                }
                Bukkit.broadcast(prefix().append(Component.text(
                        player.getName() + " has drunk their " + ordinal(threshold) + " pint!  (+"
                                + reward + " points)", NamedTextColor.GOLD)));
                GuabSounds.play(player, GuabSounds.MILESTONE, 1.0f, 1.0f);
                dirty = true;
                hologramDirty = true;
            }
        }
    }

    private Map<Integer, Integer> milestones() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("pub.milestones.rewards");
        if (section == null) {
            return DEFAULT_MILESTONES;
        }
        Map<Integer, Integer> rewards = new HashMap<>();
        for (String key : section.getKeys(false)) {
            try {
                rewards.put(Integer.parseInt(key), Math.max(0, section.getInt(key)));
            } catch (NumberFormatException ignored) {
                // Ignore malformed milestones instead of failing the pub.
            }
        }
        return rewards.isEmpty() ? DEFAULT_MILESTONES : rewards;
    }

    private void spawnHologram() {
        removeHologram();
        if (!plugin.getConfig().getBoolean("pub.pints.leaderboard.enabled", true)) {
            return;
        }
        Location location = hologramLocation();
        if (location == null) {
            return;
        }
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
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
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
        text.append("\n&8").append(totalPints()).append(" pints served");
        hologram.text(LEGACY.deserialize(text.toString()));
    }

    private Location hologramLocation() {
        String world = plugin.getConfig().getString("pub.pints.leaderboard.world", "guab_casino");
        World worldInstance = Bukkit.getWorld(world);
        if (worldInstance == null) {
            return null;
        }
        return new Location(worldInstance,
                plugin.getConfig().getDouble("pub.pints.leaderboard.x", -20.5),
                plugin.getConfig().getDouble("pub.pints.leaderboard.y", 67.3),
                plugin.getConfig().getDouble("pub.pints.leaderboard.z", -13.0));
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
        return Math.max(60, plugin.getConfig().getInt("pub.drunk.window-seconds", 900)) * 1000L;
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
                PlayerPints profile = new PlayerPints(entry.getString("name", "unknown"));
                profile.total = entry.getInt("pints", 0);
                profile.unitsMilli = entry.getLong("units-milli", 0L);
                List<Integer> saved = entry.getIntegerList("milestones");
                profile.milestones.addAll(saved);
                if (saved.isEmpty()) {
                    // Old profiles should not retroactively spam every milestone.
                    for (int threshold : milestones().keySet()) {
                        if (profile.total >= threshold) {
                            profile.milestones.add(threshold);
                        }
                    }
                }
                players.put(id, profile);
            } catch (IllegalArgumentException ignored) {
                // Skip malformed UUIDs rather than failing startup.
            }
        }
    }

    private void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerPints> entry : players.entrySet()) {
            String path = "players." + entry.getKey();
            PlayerPints profile = entry.getValue();
            yaml.set(path + ".name", profile.name);
            yaml.set(path + ".pints", profile.total);
            yaml.set(path + ".units-milli", profile.unitsMilli);
            yaml.set(path + ".milestones", new ArrayList<>(profile.milestones));
        }
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save pints.yml: " + exception.getMessage());
        }
    }

    private static String ordinal(int value) {
        if (value % 100 >= 11 && value % 100 <= 13) {
            return value + "th";
        }
        return switch (value % 10) {
            case 1 -> value + "st";
            case 2 -> value + "nd";
            case 3 -> value + "rd";
            default -> value + "th";
        };
    }

    private PointsService points() {
        var registration = Bukkit.getServicesManager().getRegistration(PointsService.class);
        return registration == null ? null : registration.getProvider();
    }

    private Component prefix() {
        return Component.text("[Spoons] ", NamedTextColor.GOLD);
    }

    private static final class PlayerPints {
        private String name;
        private int total;
        private long unitsMilli;
        private double peakUnits;
        private long hangoverUntil;
        private final Deque<Drink> recent = new ArrayDeque<>();
        private final Set<Integer> milestones = new HashSet<>();

        private PlayerPints(String name) {
            this.name = name;
        }
    }

    private record Drink(long at, int unitsMilli, String styleId) {
    }
}
