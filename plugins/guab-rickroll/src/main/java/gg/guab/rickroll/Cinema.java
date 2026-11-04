package gg.guab.rickroll;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public final class Cinema {

    private static final int FLOOR_Y = 100;
    private static final int CEILING_Y = 108;
    private static final int MIN_X = 0;
    private static final int MAX_X = 20;
    private static final int MIN_Z = 0;
    private static final int MAX_Z = 14;

    private final GuabRickrollPlugin plugin;
    private final NamespacedKey screenKey;
    private World world;
    private List<ItemStack> frames = List.of();

    public Cinema(GuabRickrollPlugin plugin) {
        this.plugin = plugin;
        this.screenKey = new NamespacedKey(plugin, "screen");
    }

    public void reload() {
        frames = List.of();
    }

    public World world() {
        if (world != null) {
            return world;
        }
        World existing = Bukkit.getWorld(plugin.config().worldName());
        if (existing != null) {
            world = existing;
            buildRoom();
            return world;
        }
        WorldCreator creator = new WorldCreator(plugin.config().worldName())
                .type(WorldType.FLAT)
                .generatorSettings("{\"layers\":[],\"biome\":\"minecraft:the_void\"}")
                .generateStructures(false)
                .environment(World.Environment.NORMAL);
        world = creator.createWorld();
        if (world == null) {
            throw new IllegalStateException("Could not create the cinema world");
        }
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setTime(18000);
        world.setStorm(false);
        world.setThundering(false);
        buildRoom();
        return world;
    }

    public Location seat() {
        return new Location(world(), 10.5, FLOOR_Y + 1, 10.5, 180.0f, 0.0f);
    }

    public ItemDisplay spawnScreen() {
        Location location = new Location(world(), 10.5, FLOOR_Y + 4.5, 1.05, 0.0f, 0.0f);
        return world().spawn(location, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.setBillboard(Display.Billboard.FIXED);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setViewRange(4.0f);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setRotation(0.0f, 0.0f);
            display.setTransformation(new Transformation(
                    new Vector3f(0.0f, 0.0f, 0.0f),
                    new AxisAngle4f(),
                    new Vector3f((float) plugin.config().screenWidth(), (float) plugin.config().screenHeight(), 1.0f),
                    new AxisAngle4f()));
            display.getPersistentDataContainer().set(screenKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    public List<ItemStack> frames() {
        if (!frames.isEmpty()) {
            return frames;
        }
        int count = plugin.config().frameCount();
        List<ItemStack> built = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            ItemStack stack = new ItemStack(Material.PAPER);
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("guab", String.format("frame/%04d", index)));
            built.add(stack);
        }
        frames = List.copyOf(built);
        return frames;
    }

    public void cleanup() {
        if (world == null) {
            return;
        }
        for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
            if (display.getPersistentDataContainer().has(screenKey, PersistentDataType.BYTE)) {
                display.remove();
            }
        }
    }

    private void buildRoom() {
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                set(x, FLOOR_Y, z, Material.RED_CONCRETE);
                set(x, CEILING_Y, z, Material.BLACK_CONCRETE);
            }
        }
        for (int y = FLOOR_Y; y <= CEILING_Y; y++) {
            for (int x = MIN_X; x <= MAX_X; x++) {
                set(x, y, MIN_Z, Material.BLACK_CONCRETE);
                set(x, y, MAX_Z, Material.BLACK_CONCRETE);
            }
            for (int z = MIN_Z + 1; z < MAX_Z; z++) {
                set(MIN_X, y, z, Material.BLACK_CONCRETE);
                set(MAX_X, y, z, Material.BLACK_CONCRETE);
            }
        }
        for (int x = 6; x <= 14; x++) {
            for (int z : new int[] { 9, 11 }) {
                set(x, FLOOR_Y + 1, z, Material.DARK_OAK_STAIRS);
            }
        }
        for (int z = 6; z <= 13; z++) {
            set(10, FLOOR_Y + 1, z, Material.RED_CARPET);
        }
    }

    private void set(int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }
}
