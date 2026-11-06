package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The dedicated darts room. Players are teleported to an oche, given a bow, and
 * shoot arrows at the target block. The arrow impact is scored by the same
 * {@link DartsGeometry} as the old pub board.
 */
public final class DartsRoom implements Listener {

    private static final String WORLD_NAME = "guab_darts";
    private static final int TARGET_X = 0;
    private static final int TARGET_Y = 66;
    private static final int TARGET_Z = -6;
    public static final double BOARD_RADIUS = 1.5;
    private static final int[] GLASS_LIGHTS = {-3, 0, 3};
    private static final double[][] OCHES = {
            {0.5, 6.5}, {-2.5, 6.5}, {3.5, 6.5}, {0.5, 4.5}
    };

    private final GuabItemsPlugin plugin;
    private final NamespacedKey bowKey;
    private final NamespacedKey arrowKey;
    private final NamespacedKey signKey;
    private final NamespacedKey boardKey;
    private final NamespacedKey liveKey;
    private final Map<UUID, ReturnState> returns = new HashMap<>();
    private World world;
    private ItemDisplay boardDisplay;
    private TextDisplay liveBoard;
    private boolean built;

    public DartsRoom(GuabItemsPlugin plugin) {
        this.plugin = plugin;
        this.bowKey = new NamespacedKey(plugin, "darts_bow");
        this.arrowKey = new NamespacedKey(plugin, "darts_arrow");
        this.signKey = new NamespacedKey(plugin, "darts_room_sign");
        this.boardKey = new NamespacedKey(plugin, "darts_room_board");
        this.liveKey = new NamespacedKey(plugin, "darts_room_live");
    }

    public boolean enter(Player player) {
        World roomWorld = world();
        if (roomWorld == null) {
            player.sendMessage(Component.text("[Darts] ", NamedTextColor.GOLD)
                    .append(Component.text("The darts room could not be created.", NamedTextColor.RED)));
            return false;
        }
        if (returns.containsKey(player.getUniqueId()) && isInRoom(player)) {
            return true;
        }
        int slot = returns.size() % OCHES.length;
        returns.put(player.getUniqueId(), new ReturnState(player.getLocation().clone(), player.getGameMode()));
        player.teleport(oche(roomWorld, slot));
        player.setGameMode(GameMode.ADVENTURE);
        giveKit(player);
        player.showTitle(net.kyori.adventure.title.Title.title(
                Component.text("DARTS", NamedTextColor.GOLD),
                Component.text("Aim at the target · 3 darts · double out", NamedTextColor.GRAY),
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(150),
                        java.time.Duration.ofMillis(1100),
                        java.time.Duration.ofMillis(350))));
        player.sendMessage(Component.text("[Darts] ", NamedTextColor.GOLD)
                .append(Component.text("You are locked on the oche. Shoot the target with the bow. /darts quit to leave.",
                        NamedTextColor.GRAY)));
        return true;
    }

    public void exit(Player player) {
        ReturnState state = returns.remove(player.getUniqueId());
        removeKit(player);
        if (state != null) {
            player.teleport(state.location());
            player.setGameMode(state.gameMode());
        } else {
            Location fallback = fallback();
            if (fallback != null) {
                player.teleport(fallback);
            }
        }
        player.sendMessage(Component.text("[Darts] ", NamedTextColor.GOLD)
                .append(Component.text("Back to the pub.", NamedTextColor.GRAY)));
    }

    /** Called on disconnect: keep the return point for the next login. */
    public void handleQuit(Player player) {
        removeKit(player);
    }

    public boolean isInRoom(Player player) {
        return player.getWorld().getName().equals(WORLD_NAME);
    }

    public Location targetCentre() {
        World roomWorld = world();
        if (roomWorld == null) {
            return null;
        }
        return DartsGeometry.centre(new Location(roomWorld, TARGET_X, TARGET_Y, TARGET_Z), BlockFace.SOUTH);
    }

    private boolean isTarget(Block block) {
        return block.getWorld().getName().equals(WORLD_NAME)
                && block.getX() >= TARGET_X - 1 && block.getX() <= TARGET_X + 1
                && block.getY() >= TARGET_Y - 1 && block.getY() <= TARGET_Y + 1
                && block.getZ() == TARGET_Z
                && block.getType() == Material.TARGET;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)
                || !(arrow.getShooter() instanceof Player player)
                || event.getHitBlock() == null
                || event.getHitBlockFace() != BlockFace.SOUTH
                || !isTarget(event.getHitBlock())
                || !plugin.darts().isPlaying(player.getUniqueId())
                || !isInRoom(player)) {
            return;
        }
        Location point = arrow.getLocation().clone();
        arrow.setPickupStatus(Arrow.PickupStatus.DISALLOWED);
        arrow.setPersistent(false);
        Bukkit.getScheduler().runTaskLater(plugin, arrow::remove, 100L);
        plugin.darts().scoreArrow(player, point);
    }

    /** Lock the player to their oche while a leg is live; looking is still free. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isInRoom(player) || !plugin.darts().isPlaying(player.getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() == null || to.getWorld() == null
                || !from.getWorld().equals(to.getWorld())
                || from.distanceSquared(to) > 4.0) {
            return;
        }
        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            fixed.setY(to.getY());
            event.setTo(fixed);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isInRoom(player) || plugin.darts().isPlaying(player.getUniqueId())) {
            return;
        }
        ReturnState state = returns.remove(player.getUniqueId());
        removeKit(player);
        Location destination = state == null ? fallback() : state.location();
        if (destination != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.teleport(destination);
                player.setGameMode(state == null ? GameMode.SURVIVAL : state.gameMode());
            });
        }
    }

    private World world() {
        if (world != null) {
            return world;
        }
        World existing = Bukkit.getWorld(WORLD_NAME);
        if (existing == null) {
            existing = new WorldCreator(WORLD_NAME)
                    .type(WorldType.FLAT)
                    .generateStructures(false)
                    .environment(World.Environment.NORMAL)
                    .createWorld();
        }
        if (existing == null) {
            return null;
        }
        world = existing;
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.ENTITY_DROPS, false);
        world.setGameRule(GameRules.BLOCK_DROPS, false);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRules.PVP, false);
        world.setStorm(false);
        world.setTime(6000L);
        world.setSpawnLocation(spawn(world));
        if (!built) {
            build(world);
            built = true;
        }
        return world;
    }

    private Location spawn(World roomWorld) {
        return oche(roomWorld, 0);
    }

    private Location oche(World roomWorld, int slot) {
        double[] point = OCHES[Math.floorMod(slot, OCHES.length)];
        return new Location(roomWorld, point[0], 65.0, point[1], 180.0f, 0.0f);
    }

    private Location fallback() {
        World casino = Bukkit.getWorld("guab_casino");
        if (casino != null) {
            return casino.getSpawnLocation();
        }
        if (Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    private void build(World roomWorld) {
        for (int x = -6; x <= 6; x++) {
            for (int y = 64; y <= 70; y++) {
                for (int z = -7; z <= 8; z++) {
                    boolean shell = x == -6 || x == 6 || y == 64 || y == 70 || z == -7 || z == 8;
                    roomWorld.getBlockAt(x, y, z).setType(shell
                            ? Material.POLISHED_DEEPSLATE : Material.AIR, false);
                }
            }
        }
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 7; z++) {
                roomWorld.getBlockAt(x, 65, z).setType(
                        z == 4 && Math.abs(x) <= 4 ? Material.RED_CARPET : Material.DARK_OAK_PLANKS, false);
            }
        }
        for (int x : GLASS_LIGHTS) {
            for (int z : GLASS_LIGHTS) {
                roomWorld.getBlockAt(x, 70, z).setType(Material.SEA_LANTERN, false);
            }
        }
        for (int x = TARGET_X - 1; x <= TARGET_X + 1; x++) {
            for (int y = TARGET_Y - 1; y <= TARGET_Y + 1; y++) {
                roomWorld.getBlockAt(x, y, TARGET_Z).setType(Material.TARGET, false);
            }
        }
        cleanupSigns(roomWorld);
        spawnBoardDisplay(roomWorld);
        spawnLiveBoard(roomWorld);
        sign(roomWorld, new Location(roomWorld, 0.5, 68.0, 6.0),
                Component.text("DARTS", NamedTextColor.GOLD)
                        .append(Component.newline())
                        .append(Component.text("Aim at the target", NamedTextColor.WHITE))
                        .append(Component.newline())
                        .append(Component.text("3 darts · double out", NamedTextColor.GRAY))
                        .append(Component.newline())
                        .append(Component.text("/darts quit to leave", NamedTextColor.DARK_GRAY)));
        sign(roomWorld, new Location(roomWorld, 0.5, 68.4, -5.4),
                Component.text("WETHERSPOONS DARTS", NamedTextColor.GOLD));
        roomWorld.getChunkAt(0, 0).load();
        roomWorld.getChunkAt(TARGET_X >> 4, TARGET_Z >> 4).load();
    }

    private void sign(World roomWorld, Location location, Component text) {
        TextDisplay display = roomWorld.spawn(location, TextDisplay.class, entity -> {
            entity.text(text);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setSeeThrough(false);
            entity.setShadowed(true);
            entity.setPersistent(false);
            entity.setViewRange(1.0f);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            entity.getPersistentDataContainer().set(signKey, PersistentDataType.BYTE, (byte) 1);
        });
        display.setPersistent(false);
    }

    private void cleanupSigns(World roomWorld) {
        for (var entity : roomWorld.getNearbyEntities(new Location(roomWorld, 0.5, 66, 0), 12, 12, 12)) {
            if (entity instanceof TextDisplay text
                    && text.getPersistentDataContainer().has(signKey, PersistentDataType.BYTE)) {
                text.remove();
            }
            if (entity.getPersistentDataContainer().has(boardKey, PersistentDataType.BYTE)
                    || entity.getPersistentDataContainer().has(liveKey, PersistentDataType.BYTE)) {
                entity.remove();
            }
        }
        boardDisplay = null;
        liveBoard = null;
    }

    private void spawnBoardDisplay(World roomWorld) {
        Location location = new Location(roomWorld, 0.5, 66.5, -4.97);
        boardDisplay = roomWorld.spawn(location, ItemDisplay.class, display -> {
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            NamespacedKey model = NamespacedKey.fromString("guab:dartboard");
            if (model != null) {
                meta.setItemModel(model);
            }
            item.setItemMeta(meta);
            display.setItemStack(item);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setBillboard(Display.Billboard.CENTER);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setPersistent(false);
            display.setViewRange(1.0f);
            display.setTransformation(new Transformation(
                    new Vector3f(), new AxisAngle4f(),
                    new Vector3f(3.0f, 3.0f, 1.0f), new AxisAngle4f()));
            display.getPersistentDataContainer().set(boardKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private void spawnLiveBoard(World roomWorld) {
        Location location = new Location(roomWorld, 0.5, 68.2, 2.5);
        liveBoard = roomWorld.spawn(location, TextDisplay.class, display -> {
            display.text(Component.text("DARTS  ·  LIVE HITS", NamedTextColor.GOLD)
                    .append(Component.newline())
                    .append(Component.text("Waiting for players", NamedTextColor.GRAY)));
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(false);
            display.setShadowed(true);
            display.setPersistent(true);
            display.setViewRange(1.0f);
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
            display.getPersistentDataContainer().set(liveKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Redraw the shared hit board between the oches and the target. */
    public void updateLiveBoard(Collection<DartsGame> games) {
        World roomWorld = world();
        if (roomWorld == null) {
            return;
        }
        if (liveBoard == null || !liveBoard.isValid()) {
            spawnLiveBoard(roomWorld);
        }
        if (liveBoard == null || !liveBoard.isValid()) {
            return;
        }
        Component text = Component.text("DARTS  ·  LIVE HITS", NamedTextColor.GOLD);
        if (games.isEmpty()) {
            text = text.append(Component.newline())
                    .append(Component.text("Waiting for players", NamedTextColor.GRAY));
        } else {
            for (DartsGame game : games) {
                String hit = game.lastHit() == null ? "—" : game.lastHit().label();
                text = text.append(Component.newline())
                        .append(Component.text(game.playerName(), NamedTextColor.WHITE))
                        .append(Component.text("  " + game.score() + " left  ·  " + hit,
                                NamedTextColor.GRAY));
            }
        }
        liveBoard.text(text);
    }

    private void giveKit(Player player) {
        removeKit(player);
        PlayerInventory inventory = player.getInventory();

        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta bowMeta = bow.getItemMeta();
        bowMeta.displayName(Component.text("Darts Bow", NamedTextColor.GOLD));
        bowMeta.setUnbreakable(true);
        bowMeta.getPersistentDataContainer().set(bowKey, PersistentDataType.BYTE, (byte) 1);
        bow.setItemMeta(bowMeta);
        inventory.addItem(bow).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));

        ItemStack arrows = new ItemStack(Material.ARROW, 64);
        ItemMeta arrowMeta = arrows.getItemMeta();
        arrowMeta.displayName(Component.text("Darts Arrows", NamedTextColor.GRAY));
        arrowMeta.getPersistentDataContainer().set(arrowKey, PersistentDataType.BYTE, (byte) 1);
        arrows.setItemMeta(arrowMeta);
        inventory.addItem(arrows).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void removeKit(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null) {
                continue;
            }
            if (item.getItemMeta().getPersistentDataContainer().has(bowKey, PersistentDataType.BYTE)
                    || item.getItemMeta().getPersistentDataContainer().has(arrowKey, PersistentDataType.BYTE)) {
                inventory.setItem(slot, null);
            }
        }
    }

    private record ReturnState(Location location, GameMode gameMode) {
    }
}
