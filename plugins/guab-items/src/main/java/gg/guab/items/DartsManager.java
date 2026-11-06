package gg.guab.items;

import gg.guab.core.PointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the dartboard: live legs, aim scoring, HUD, career records and the
 * showpiece effects around the board.
 */
public final class DartsManager implements Listener {

    private final GuabItemsPlugin plugin;
    private final DartsStatsStore store;
    private final DartsRoom room;
    private final Map<UUID, DartsGame> games = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final NamespacedKey displayKey;
    private final NamespacedKey dartKey;

    private DartsConfig config;
    private TextDisplay display;
    private BukkitTask hudTask;
    private BukkitTask saveTask;

    public DartsManager(GuabItemsPlugin plugin) {
        this.plugin = plugin;
        this.store = new DartsStatsStore(plugin);
        this.room = new DartsRoom(plugin);
        this.displayKey = new NamespacedKey(plugin, "darts_board_display");
        this.dartKey = new NamespacedKey(plugin, "darts_throw_display");
        this.config = DartsConfig.load(plugin);
    }

    public void start() {
        store.load();
        removeLeftoverDisplays();
        hudTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick,
                20L, Math.max(1, config.hudIntervalTicks()));
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, store::saveIfDirty,
                20L * 60, 20L * 60);
    }

    public void stop() {
        if (hudTask != null) {
            hudTask.cancel();
            hudTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        store.save();
        games.clear();
        removeDisplay();
    }

    public void reload() {
        endAll("The board was reset while the rules were being changed.");
        this.config = DartsConfig.load(plugin);
        removeLeftoverDisplays();
        updateDisplay();
    }

    public DartsConfig config() {
        return config;
    }

    public DartsRoom room() {
        return room;
    }

    public DartsStatsStore.Stats stats(UUID player) {
        return store.stats(player);
    }

    public List<Map.Entry<String, Integer>> top(String metric, int limit) {
        return store.top(metric, limit);
    }

    public boolean isPlaying(UUID player) {
        return games.containsKey(player);
    }

    public void openMenu(Player player) {
        if (!config.enabled()) {
            actionBar(player, Component.text("The dartboard is closed.", NamedTextColor.RED));
            return;
        }
        DartsDialog.openMenu(plugin, player);
    }

    public void startDefault(Player player) {
        startGame(player, config.defaultMode(), config.legsToWin());
    }

    public boolean startGame(Player player, int mode, int legsToWin) {
        if (!config.enabled()) {
            actionBar(player, Component.text("The dartboard is closed.", NamedTextColor.RED));
            return false;
        }
        if (!player.hasPermission("guab.pub.darts.use")) {
            actionBar(player, Component.text("You are not allowed to play darts.", NamedTextColor.RED));
            return false;
        }
        if (games.containsKey(player.getUniqueId())) {
            actionBar(player, Component.text("You already have a leg on the board.", NamedTextColor.YELLOW));
            return false;
        }
        if (!games.isEmpty()) {
            actionBar(player, Component.text("Someone else is throwing. Wait for the board.", NamedTextColor.YELLOW));
            return false;
        }
        int normalisedMode = mode == 101 || mode == 301 || mode == 501 ? mode : config.defaultMode();
        int normalisedLegs = Math.clamp(legsToWin, 1, 9);
        DartsGame game = new DartsGame(player.getUniqueId(), player.getName(), normalisedMode, normalisedLegs);
        games.put(player.getUniqueId(), game);
        store.recordLegStart(player.getUniqueId(), player.getName());
        updateDisplay();

        player.showTitle(Title.title(
                Component.text("GAME ON", NamedTextColor.GOLD),
                Component.text(normalisedMode + " · double out", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(900), Duration.ofMillis(400))));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.8f, 1.4f);
        send(player, "Leg on: " + normalisedMode + ". Take the bow, aim at the target. "
                + "Three darts, double out. Good luck.", NamedTextColor.GOLD);
        showHud(player, game);
        if (!room.enter(player)) {
            games.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    /** Arrow impact in the dedicated darts room. */
    public void scoreArrow(Player player, Location point) {
        DartsGame game = games.get(player.getUniqueId());
        Location centre = room.targetCentre();
        if (game == null || centre == null || point == null) {
            return;
        }
        resolveThrow(player, game, point, centre, BlockFace.SOUTH, 0.5);
    }

    /** Legacy pub-board throw, kept as a fallback if the room is unavailable. */
    public void throwDart(Player player, Location point) {
        DartsGame game = games.get(player.getUniqueId());
        if (game == null) {
            openMenu(player);
            return;
        }
        if (point == null) {
            actionBar(player, Component.text("Aim at the target and shoot again.", NamedTextColor.GRAY));
            return;
        }
        Location centre = boardCentre();
        if (centre == null || point.getWorld() == null || !point.getWorld().equals(centre.getWorld())) {
            actionBar(player, Component.text("The board is not in this world.", NamedTextColor.RED));
            return;
        }
        if (!player.getWorld().equals(centre.getWorld())
                || player.getLocation().distanceSquared(centre)
                > config.maxDistance() * config.maxDistance()) {
            actionBar(player, Component.text("Stand closer to the oche.", NamedTextColor.RED));
            return;
        }
        resolveThrow(player, game, point, centre, config.face(), config.boardRadius());
    }

    private void resolveThrow(Player player, DartsGame game, Location point,
                              Location centre, BlockFace face, double radius) {
        long now = System.currentTimeMillis();
        long remaining = cooldowns.getOrDefault(player.getUniqueId(), 0L) - now;
        if (remaining > 0L) {
            actionBar(player, Component.text("Steady... " + remaining + "ms", NamedTextColor.GRAY));
            return;
        }
        cooldowns.put(player.getUniqueId(), now + config.cooldownMillis());

        double drunk = plugin.pub() == null ? 0 : plugin.pub().impairment(player.getUniqueId()) * 10.0;
        double wobble = Math.min(config.wobbleMax(), config.wobbleSober() + drunk * config.wobblePerDrunk());
        DartsGeometry.Hit hit = DartsGeometry.score(centre, face, radius, point, config, wobble);

        DartsGame.Result result = game.throwDart(hit);
        store.recordThrow(player.getUniqueId());
        if (result.kind() == DartsGame.Kind.TURN_END) {
            store.recordVisit(player.getUniqueId(), result.turnTotal());
        }
        if (result.oneEighty()) {
            store.record180(player.getUniqueId());
        }
        if (result.kind() == DartsGame.Kind.CHECKOUT) {
            store.recordCheckout(player.getUniqueId(), result.legDarts(), result.checkout());
        }

        showHud(player, game);
        present(player, game, result, point);

        if (result.kind() == DartsGame.Kind.CHECKOUT) {
            int reward = reward(player, game, result);
            String payout = reward > 0 ? "  (+" + reward + " points)" : "";
            announce(player, player.getName() + " checked out " + result.checkout()
                    + " in " + result.legDarts() + " darts" + payout);
            if (result.matchWon()) {
                games.remove(player.getUniqueId());
                updateDisplay();
                send(player, "Match won " + result.legsWon() + "–0. The bar is impressed.", NamedTextColor.GOLD);
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE,
                        SoundCategory.PLAYERS, 1.0f, 1.0f);
                if (room.isInRoom(player)) {
                    room.exit(player);
                }
            } else {
                store.recordLegStart(player.getUniqueId(), player.getName());
                send(player, "Leg won. Next leg: " + game.mode() + ". Stay on the oche.", NamedTextColor.GREEN);
            }
        } else if (result.kind() == DartsGame.Kind.BUST) {
            send(player, "Bust. Back to " + result.scoreAfter() + ".", NamedTextColor.RED);
        } else if (result.oneEighty()) {
            announce(player, player.getName() + " hit ONE HUNDRED AND EIGHTY!");
        }

        updateDisplay();
    }

    public void endGame(Player player, String reason) {
        if (games.remove(player.getUniqueId()) != null) {
            send(player, reason, NamedTextColor.GRAY);
            if (room.isInRoom(player)) {
                room.exit(player);
            }
            updateDisplay();
        }
    }

    public void endAll(String reason) {
        if (games.isEmpty()) {
            return;
        }
        for (UUID id : new ArrayList<>(games.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                send(player, reason, NamedTextColor.GRAY);
                if (room.isInRoom(player)) {
                    room.exit(player);
                }
            }
        }
        games.clear();
        updateDisplay();
    }

    public boolean isBoard(Block block, Location point) {
        if (block == null || block.getWorld() == null) {
            return false;
        }
        if (!block.getWorld().getName().equals(config.world())) {
            return false;
        }
        if (block.getX() == config.x() && block.getY() == config.y() && block.getZ() == config.z()) {
            return true;
        }
        Location centre = boardCentre();
        return point != null && centre != null && point.getWorld() != null
                && point.getWorld().equals(centre.getWorld())
                && point.distanceSquared(centre) <= (config.boardRadius() + 0.2) * (config.boardRadius() + 0.2);
    }

    public void setBoard(Block block, BlockFace face) {
        if (face != BlockFace.NORTH && face != BlockFace.EAST
                && face != BlockFace.SOUTH && face != BlockFace.WEST) {
            face = BlockFace.EAST;
        }
        plugin.getConfig().set("pub.darts.board.world", block.getWorld().getName());
        plugin.getConfig().set("pub.darts.board.x", block.getX());
        plugin.getConfig().set("pub.darts.board.y", block.getY());
        plugin.getConfig().set("pub.darts.board.z", block.getZ());
        plugin.getConfig().set("pub.darts.board.face", face.name());
        plugin.saveConfig();
        reload();
    }

    public Location boardCentre() {
        World world = Bukkit.getWorld(config.world());
        if (world == null) {
            return null;
        }
        return DartsGeometry.centre(new Location(world, config.x(), config.y(), config.z()), config.face());
    }

    public int balance(UUID player) {
        PointsService points = points();
        return points == null ? 0 : points.get(player);
    }

    private void tick() {
        if (games.isEmpty()) {
            return;
        }
        for (DartsGame game : new ArrayList<>(games.values())) {
            Player player = Bukkit.getPlayer(game.playerId());
            if (player == null || !player.isOnline()) {
                games.remove(game.playerId());
                continue;
            }
            showHud(player, game);
        }
        updateDisplay();
    }

    private void showHud(Player player, DartsGame game) {
        String hint = game.checkoutHint();
        Component line = Component.text("DARTS ", NamedTextColor.GOLD)
                .append(Component.text(String.valueOf(game.mode()), NamedTextColor.YELLOW))
                .append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(game.score() + " left", NamedTextColor.WHITE))
                .append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(game.dartsLeft() + " darts", NamedTextColor.AQUA))
                .append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                .append(Component.text("L" + game.legNumber() + "/" + game.legsToWin(), NamedTextColor.GRAY));
        if (hint != null) {
            line = line.append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(hint, NamedTextColor.GREEN));
        }
        player.sendActionBar(line);
    }

    private void present(Player player, DartsGame game, DartsGame.Result result, Location point) {
        Location centre = boardCentre();
        if (centre != null) {
            World world = centre.getWorld();
            world.spawnParticle(Particle.DUST, point, 18, 0.18, 0.18, 0.18, 0.0,
                    new Particle.DustOptions(Color.fromRGB(0xF4C542), 0.9f));
            world.spawnParticle(Particle.CRIT, point, 10, 0.12, 0.12, 0.12, 0.02);
            spawnDart(point);
        }

        if (result.kind() == DartsGame.Kind.BUST) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,
                    SoundCategory.PLAYERS, 0.8f, 0.6f);
        } else if (result.kind() == DartsGame.Kind.CHECKOUT) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE,
                    SoundCategory.PLAYERS, 0.9f, 1.2f);
            celebration(centre, result.oneEighty() ? 3 : 2);
        } else if (result.oneEighty()) {
            player.showTitle(Title.title(
                    Component.text("180!", NamedTextColor.GOLD),
                    Component.text("three in the treble twenty", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1200), Duration.ofMillis(350))));
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST,
                    SoundCategory.PLAYERS, 1.0f, 0.8f);
            celebration(centre, 2);
        } else if (result.hit().number() == 20 && result.hit().multiplier() == 3) {
            player.showTitle(Title.title(
                    Component.text("TRIPLE 20", NamedTextColor.GOLD),
                    Component.text("60", NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(650), Duration.ofMillis(200))));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,
                    SoundCategory.PLAYERS, 1.0f, 1.6f);
        } else if (result.hit().isBullseye()) {
            player.showTitle(Title.title(
                    Component.text("BULLSEYE", NamedTextColor.RED),
                    Component.text("50", NamedTextColor.GOLD),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(700), Duration.ofMillis(250))));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                    SoundCategory.PLAYERS, 1.0f, 0.7f);
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_ARROW_HIT,
                    SoundCategory.PLAYERS, 0.9f, 1.2f);
        }

        if (result.kind() == DartsGame.Kind.BUST) {
            player.sendActionBar(Component.text("BUST", NamedTextColor.RED)
                    .append(Component.text("  ·  back to " + result.scoreAfter(), NamedTextColor.GRAY)));
        } else if (result.kind() == DartsGame.Kind.CHECKOUT) {
            player.sendActionBar(Component.text("CHECKOUT " + result.checkout()
                    + " in " + result.legDarts() + " darts", NamedTextColor.GOLD));
        } else if (result.kind() == DartsGame.Kind.TURN_END) {
            player.sendActionBar(Component.text("Visit " + result.turnTotal(), NamedTextColor.WHITE)
                    .append(Component.text("  ·  " + result.scoreAfter() + " left", NamedTextColor.GRAY))
                    .append(Component.text("  ·  new turn", NamedTextColor.AQUA)));
        } else {
            player.sendActionBar(Component.text(result.hit().label(), NamedTextColor.WHITE)
                    .append(Component.text("  ·  " + result.scoreAfter() + " left", NamedTextColor.GRAY))
                    .append(Component.text("  ·  darts " + game.dartsLeft() + "/3", NamedTextColor.AQUA)));
        }
    }

    private void spawnDart(Location point) {
        if (point.getWorld() == null) {
            return;
        }
        Location location = point.clone().add(config.face().getDirection().clone().multiply(0.08));
        ItemDisplay dart = point.getWorld().spawn(location, ItemDisplay.class, entity -> {
            entity.setItemStack(new ItemStack(Material.ARROW));
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setViewRange(0.8f);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setTransformation(new Transformation(
                    new Vector3f(), new AxisAngle4f(),
                    new Vector3f(0.65f, 0.65f, 0.65f), new AxisAngle4f()));
            entity.getPersistentDataContainer().set(dartKey, PersistentDataType.BYTE, (byte) 1);
        });
        Bukkit.getScheduler().runTaskLater(plugin, dart::remove, 60L);
    }

    private void celebration(Location centre, int rockets) {
        if (centre == null || centre.getWorld() == null) {
            return;
        }
        World world = centre.getWorld();
        for (int i = 0; i < rockets; i++) {
            double offset = i == 0 ? 0.0 : (i % 2 == 0 ? 0.8 : -0.8);
            Location location = centre.clone().add(offset, 0.4 + i * 0.2, 0.2);
            Firework firework = world.spawn(location, Firework.class, entity -> {
                FireworkMeta meta = entity.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder()
                        .with(FireworkEffect.Type.BURST)
                        .withColor(Color.YELLOW, Color.ORANGE)
                        .withFade(Color.WHITE)
                        .trail(true)
                        .flicker(true)
                        .build());
                meta.setPower(0);
                entity.setFireworkMeta(meta);
                entity.setShotAtAngle(true);
            });
            firework.detonate();
        }
    }

    private int reward(Player player, DartsGame game, DartsGame.Result result) {
        if (!config.rewards()) {
            return 0;
        }
        int base = switch (game.mode()) {
            case 101 -> config.base101();
            case 301 -> config.base301();
            default -> config.base501();
        };
        int par = switch (game.mode()) {
            case 101 -> config.par101();
            case 301 -> config.par301();
            default -> config.par501();
        };
        int underPar = Math.max(0, par - result.legDarts()) * config.underParPerDart();
        int checkout = (int) Math.round(result.checkout() * config.checkoutPerPoint());
        int total = Math.min(config.maxReward(), base + underPar + checkout);
        if (total > 0) {
            PointsService points = points();
            if (points != null) {
                points.add(player.getUniqueId(), total);
            }
        }
        return total;
    }

    private void announce(Player source, String message) {
        Location centre = boardCentre();
        if (centre == null) {
            return;
        }
        Component line = Component.text("[Darts] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.WHITE));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(centre.getWorld())
                    && player.getLocation().distanceSquared(centre) <= 48.0 * 48.0) {
                player.sendMessage(line);
            }
        }
        if (!source.getWorld().equals(centre.getWorld())) {
            source.sendMessage(line);
        }
    }

    private void updateDisplay() {
        if (!config.enabled() || games.isEmpty()) {
            removeDisplay();
            return;
        }
        Location centre = boardCentre();
        if (centre == null || centre.getWorld() == null) {
            removeDisplay();
            return;
        }
        World world = centre.getWorld();
        if (display == null || !display.isValid()) {
            Location location = centre.clone().add(0, 1.25, 0)
                    .add(config.face().getDirection().clone().multiply(0.05));
            display = world.spawn(location, TextDisplay.class, entity -> {
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setSeeThrough(false);
                entity.setShadowed(true);
                entity.setPersistent(false);
                entity.setViewRange(0.9f);
                entity.setDefaultBackground(false);
                entity.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                entity.setLineWidth(200);
                entity.setAlignment(TextDisplay.TextAlignment.CENTER);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.getPersistentDataContainer().set(displayKey, PersistentDataType.BYTE, (byte) 1);
            });
        }
        DartsGame game = games.values().iterator().next();
        String hint = game.checkoutHint();
        Component text = Component.text("WETHERSPOONS DARTS", NamedTextColor.GOLD)
                .append(Component.text("\n" + game.playerName() + "  ·  " + game.score() + " left",
                        NamedTextColor.WHITE))
                .append(Component.text("\nLeg " + game.legNumber() + "/" + game.legsToWin()
                        + "  ·  darts " + game.dartsLeft() + "/3", NamedTextColor.GRAY))
                .append(Component.text("\n" + (hint == null ? "no checkout" : "checkout  " + hint),
                        hint == null ? NamedTextColor.DARK_GRAY : NamedTextColor.GREEN));
        display.text(text);
    }

    private void removeDisplay() {
        if (display != null) {
            display.remove();
            display = null;
        }
    }

    private void removeLeftoverDisplays() {
        Location centre = boardCentre();
        if (centre == null || centre.getWorld() == null) {
            return;
        }
        for (var entity : centre.getWorld().getNearbyEntities(centre, 6, 6, 6)) {
            if (entity instanceof TextDisplay text
                    && text.getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE)) {
                text.remove();
            }
            if (entity instanceof ItemDisplay item
                    && item.getPersistentDataContainer().has(dartKey, PersistentDataType.BYTE)) {
                item.remove();
            }
        }
        display = null;
    }

    private PointsService points() {
        var registration = Bukkit.getServicesManager().getRegistration(PointsService.class);
        return registration == null ? null : registration.getProvider();
    }

    private void send(Player player, String message, NamedTextColor colour) {
        player.sendMessage(Component.text("[Darts] ", NamedTextColor.GOLD)
                .append(Component.text(message, colour)));
    }

    private void actionBar(Player player, Component message) {
        player.sendActionBar(message);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cooldowns.remove(id);
        if (games.remove(id) != null) {
            room.handleQuit(event.getPlayer());
            updateDisplay();
        }
    }
}
