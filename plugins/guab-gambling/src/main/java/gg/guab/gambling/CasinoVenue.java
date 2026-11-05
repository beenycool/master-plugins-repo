package gg.guab.gambling;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;

/**
 * Builds the walk-in casino hall. The hall is deliberately empty inside each painted
 * plot: MineGames builds its own roulette board, slots, chicken board and arena where
 * an admin stands, so we only supply the room, the lights and the marked stations.
 */
public final class CasinoVenue {

    private static final int HALF_WIDTH = 25;
    private static final int HALF_DEPTH = 17;
    private static final int HEIGHT = 9;

    private final GuabGamblingPlugin plugin;
    private World world;

    public CasinoVenue(GuabGamblingPlugin plugin) {
        this.plugin = plugin;
    }

    public World world() {
        if (world != null) {
            return world;
        }
        String name = plugin.config().worldName();
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            world = existing;
        } else {
            World created = new WorldCreator(name)
                    .type(WorldType.FLAT)
                    .generateStructures(false)
                    .environment(World.Environment.NORMAL)
                    .createWorld();
            if (created == null) {
                throw new IllegalStateException("Could not create the casino world " + name);
            }
            world = created;
        }
        world.setDifficulty(Difficulty.EASY);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setPVP(false);
        world.setStorm(false);
        world.setTime(6000L);
        world.setSpawnLocation(spawn());
        return world;
    }

    public Location spawn() {
        CasinoConfig config = plugin.config();
        return new Location(world(), config.spawnX() + 0.5, config.spawnY(), config.spawnZ() + 0.5, 180.0f, 0.0f);
    }

    public boolean isCasinoWorld(World other) {
        return other != null && other.getName().equals(plugin.config().worldName());
    }

    /** Rebuild the room from scratch. Safe to run repeatedly. */
    public int build() {
        World world = world();
        CasinoConfig config = plugin.config();
        int cx = config.x();
        int cy = config.y();
        int cz = config.z();

        // Clear the volume first so a rebuild never leaves stale blocks.
        for (int dx = -HALF_WIDTH; dx <= HALF_WIDTH; dx++) {
            for (int dz = -HALF_DEPTH; dz <= HALF_DEPTH; dz++) {
                for (int dy = 0; dy <= HEIGHT; dy++) {
                    set(world, cx + dx, cy + dy, cz + dz, Material.AIR);
                }
            }
        }

        // Checkerboard floor and lit ceiling.
        for (int dx = -HALF_WIDTH; dx <= HALF_WIDTH; dx++) {
            for (int dz = -HALF_DEPTH; dz <= HALF_DEPTH; dz++) {
                boolean dark = Math.floorMod(dx + dz, 2) == 0;
                set(world, cx + dx, cy, cz + dz, dark ? Material.POLISHED_BLACKSTONE : Material.POLISHED_DEEPSLATE);
            }
        }
        for (int dx = -HALF_WIDTH; dx <= HALF_WIDTH; dx++) {
            for (int dz = -HALF_DEPTH; dz <= HALF_DEPTH; dz++) {
                boolean light = Math.floorMod(dx, 6) == 0 && Math.floorMod(dz, 6) == 0;
                set(world, cx + dx, cy + HEIGHT, cz + dz, light ? Material.SEA_LANTERN : Material.SMOOTH_QUARTZ);
            }
        }

        // Walls with quartz panels and deepslate pillars.
        for (int dy = 1; dy < HEIGHT; dy++) {
            for (int dx = -HALF_WIDTH; dx <= HALF_WIDTH; dx++) {
                boolean pillar = Math.floorMod(dx, 5) == 0;
                Material material = pillar ? Material.POLISHED_BLACKSTONE : Material.SMOOTH_QUARTZ;
                set(world, cx + dx, cy + dy, cz - HALF_DEPTH, material);
                set(world, cx + dx, cy + dy, cz + HALF_DEPTH, material);
            }
            for (int dz = -HALF_DEPTH; dz <= HALF_DEPTH; dz++) {
                boolean pillar = Math.floorMod(dz, 5) == 0;
                Material material = pillar ? Material.POLISHED_BLACKSTONE : Material.SMOOTH_QUARTZ;
                set(world, cx - HALF_WIDTH, cy + dy, cz + dz, material);
                set(world, cx + HALF_WIDTH, cy + dy, cz + dz, material);
            }
        }
        for (int dx = -HALF_WIDTH; dx <= HALF_WIDTH; dx++) {
            for (int dz = -HALF_DEPTH; dz <= HALF_DEPTH; dz++) {
                boolean edgeX = dx == -HALF_WIDTH || dx == HALF_WIDTH;
                boolean edgeZ = dz == -HALF_DEPTH || dz == HALF_DEPTH;
                if (edgeX || edgeZ) {
                    set(world, cx + dx, cy + HEIGHT - 1, cz + dz, Material.GOLD_BLOCK);
                }
            }
        }

        // Red carpet runner down the aisles, kept off the play areas.
        for (int dz = -HALF_DEPTH + 1; dz <= HALF_DEPTH - 1; dz++) {
            set(world, cx - 24, cy + 1, cz + dz, Material.RED_CARPET);
            set(world, cx + 24, cy + 1, cz + dz, Material.RED_CARPET);
        }
        for (int dx = -8; dx <= 8; dx++) {
            set(world, cx + dx, cy + 1, cz - 16, Material.RED_CARPET);
        }

        // Plots. Each ring is painted on the floor; the interior stays clear for MineGames.
        plot(world, cx, cy, cz, 0, 0, 15, 15, Material.RED_CONCRETE);
        plot(world, cx, cy, cz, -15, 0, 15, 15, Material.ORANGE_CONCRETE);
        plot(world, cx, cy, cz, -15, 12, 9, 9, Material.YELLOW_CONCRETE);
        plot(world, cx, cy, cz, 19, -10, 5, 5, Material.LIME_CONCRETE);
        plot(world, cx, cy, cz, 19, -5, 5, 5, Material.LIME_CONCRETE);
        plot(world, cx, cy, cz, 19, 0, 5, 5, Material.LIME_CONCRETE);
        plot(world, cx, cy, cz, 19, 7, 5, 5, Material.LIGHT_BLUE_CONCRETE);
        plot(world, cx, cy, cz, 0, -12, 7, 5, Material.CYAN_CONCRETE);

        // The mines station needs a beacon block under the player's feet.
        set(world, cx + 19, cy, cz + 7, Material.BEACON);
        set(world, cx + 19, cy + 1, cz + 7, Material.AIR);

        // Signs sit just outside each plot so they never take up a station's floor space.
        sign(world, cx + 16, cy + 1, cz - 10, "SLOTS", "stand on pad", "/slotsadmin", "create 3 1");
        sign(world, cx + 16, cy + 1, cz - 5, "SLOTS", "stand on pad", "/slotsadmin", "create 3 1");
        sign(world, cx + 16, cy + 1, cz, "SLOTS", "stand on pad", "/slotsadmin", "create 3 1");
        sign(world, cx + 14, cy + 1, cz + 7, "MINES", "stand on beacon", "/minegameadmin", "create");
        sign(world, cx - 10, cy + 1, cz + 12, "CHICKEN", "stand on pad", "/chickenadmin", "create 7");
        sign(world, cx, cy + 1, cz + 8, "ROULETTE", "centre of board", "/rouletteadmin", "create");
        sign(world, cx - 9, cy + 1, cz, "FIGHTS", "arena origin", "/fightadmin", "create 9 2");
        sign(world, cx + 4, cy + 1, cz - 12, "DICE", "item based", "/dice", "");
        sign(world, cx + 3, cy + 1, cz + 16, "GUAB CASINO", "walk in, sit down,", "lose your points", "");

        // Bar along the north wall.
        for (int dx = -8; dx <= 8; dx++) {
            set(world, cx + dx, cy + 1, cz - 15, Material.BARREL);
            if (Math.abs(dx) % 4 == 0) {
                set(world, cx + dx, cy + 2, cz - 15, Material.LANTERN);
            }
        }

        world.setSpawnLocation(spawn());
        return (HALF_WIDTH * 2 + 1) * (HALF_DEPTH * 2 + 1) * (HEIGHT + 1);
    }

    private void plot(World world, int cx, int cy, int cz, int offsetX, int offsetZ, int width, int depth, Material edge) {
        int minX = offsetX - width / 2;
        int maxX = minX + width - 1;
        int minZ = offsetZ - depth / 2;
        int maxZ = minZ + depth - 1;
        for (int dx = minX; dx <= maxX; dx++) {
            for (int dz = minZ; dz <= maxZ; dz++) {
                boolean border = dx == minX || dx == maxX || dz == minZ || dz == maxZ;
                if (border) {
                    set(world, cx + dx, cy, cz + dz, edge);
                }
            }
        }
    }

    private void sign(World world, int x, int y, int z, String line0, String line1, String line2, String line3) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.OAK_SIGN, false);
        if (block.getState() instanceof Sign sign) {
            sign.setLine(0, "§6" + line0);
            sign.setLine(1, "§f" + line1);
            sign.setLine(2, "§7" + line2);
            sign.setLine(3, "§7" + line3);
            sign.update(true, false);
        }
    }

    private void set(World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != material) {
            block.setType(material, false);
        }
    }
}
