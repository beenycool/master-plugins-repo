package gg.guab.blocks;

import gg.guab.core.CooldownMap;
import gg.guab.core.HudChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class PrankManager {

    /** Soft convention shared with GuabMinigames; no compile-time dependency. */
    static final String PIT_METADATA = "guab.battlepit";

    private final GuabBlocksPlugin plugin;
    private final CooldownMap<UUID> playerCooldowns = new CooldownMap<>();
    private final CooldownMap<String> blockCooldowns = new CooldownMap<>();
    private final CooldownMap<UUID> swapCooldowns = new CooldownMap<>();
    private final Map<String, PendingRestore> pendingRestores = new HashMap<>();

    public PrankManager(GuabBlocksPlugin plugin) {
        this.plugin = plugin;
    }

    public void trigger(Player player, Block block, PrankBlock entry) {
        if (isExcluded(player)) {
            return;
        }
        BlocksConfig config = plugin.config();

        if (playerCooldowns.remainingMillis(player.getUniqueId()) > 0L) {
            return;
        }
        long blockCooldown = entry.cooldownSeconds() != null
                ? entry.cooldownSeconds() * 1000L
                : config.perBlockCooldownMillis();
        if (blockCooldowns.remainingMillis(entry.key()) > 0L) {
            return;
        }
        if (entry.type() == PrankType.SWAP
                && swapCooldowns.remainingMillis(player.getUniqueId()) > 0L) {
            return;
        }

        // Do not burn a player/block cooldown unless the prank actually happened.
        boolean applied = switch (entry.type()) {
            case FAKE -> fake(block, entry, config, false);
            case POTION -> potion(player, entry, config);
            case SWAP -> swap(player, config);
        };
        if (!applied) {
            return;
        }
        playerCooldowns.start(player.getUniqueId(), config.perPlayerCooldownMillis());
        blockCooldowns.start(entry.key(), blockCooldown);
    }

    /** Admin test path: ignores chance and every cooldown. */
    public boolean triggerForced(Player player, Block block, PrankBlock entry) {
        if (isExcluded(player)) {
            return false;
        }
        BlocksConfig config = plugin.config();
        return switch (entry.type()) {
            case FAKE -> fake(block, entry, config, true);
            case POTION -> potion(player, entry, config);
            case SWAP -> swap(player, config);
        };
    }

    /** Creative, spectator, dead players and battle-pit fighters are off limits. */
    public boolean isExcluded(Player player) {
        if (player.isDead() || player.getHealth() <= 0.0) {
            return true;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return true;
        }
        return player.hasMetadata(PIT_METADATA) || player.hasMetadata("battlepit");
    }

    public void forget(Player player) {
        UUID id = player.getUniqueId();
        playerCooldowns.clear(id);
        swapCooldowns.clear(id);
    }

    public void restoreAll() {
        for (String key : List.copyOf(pendingRestores.keySet())) {
            restore(key);
        }
    }

    public PendingRestoreInfo pending(Block block) {
        return pending(BlockStore.key(block));
    }

    public PendingRestoreInfo pending(String world, int x, int y, int z) {
        return pending(BlockStore.normalizeWorld(world) + ";" + x + ";" + y + ";" + z);
    }

    private PendingRestoreInfo pending(String key) {
        PendingRestore pending = pendingRestores.get(key);
        if (pending == null) {
            return null;
        }
        return new PendingRestoreInfo(pending.world, pending.x, pending.y, pending.z,
                pending.data.getAsString());
    }

    public int pendingCount() {
        return pendingRestores.size();
    }

    public boolean restoreAt(String world, int x, int y, int z) {
        return restore(BlockStore.normalizeWorld(world) + ";" + x + ";" + y + ";" + z);
    }

    public void shutdown() {
        if (plugin.config().restoreOnDisable()) {
            restoreAll();
            return;
        }
        int left = pendingRestores.size();
        for (PendingRestore pending : pendingRestores.values()) {
            if (pending.task != null) {
                pending.task.cancel();
            }
        }
        pendingRestores.clear();
        if (left > 0) {
            plugin.getLogger().warning("Left " + left + " fake block(s) as air because fake.restore-on-disable is false.");
        }
    }

    private boolean fake(Block block, PrankBlock entry, BlocksConfig config, boolean force) {
        String key = entry.key();
        if (pendingRestores.containsKey(key)) {
            return false;
        }
        if (!BlockStore.isSimpleSolid(block)) {
            plugin.getLogger().warning("Skipped fake block at " + key
                    + " because it is no longer a simple solid block.");
            return false;
        }
        double chance = entry.chance() != null ? entry.chance() : config.fakeChance();
        if (!force && (chance <= 0.0 || ThreadLocalRandom.current().nextDouble() >= chance)) {
            return false;
        }

        BlockData original = block.getBlockData().clone();
        int delay = entry.restoreDelayTicks() != null
                ? entry.restoreDelayTicks()
                : config.fakeRestoreDelayTicks();
        Sound sound = entry.breakSound() != null
                ? BlocksConfig.resolveSound(entry.breakSound(), config.fakeBreakSound(), plugin.getLogger())
                : config.fakeBreakSound();

        PendingRestore pending = new PendingRestore(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), original);
        pendingRestores.put(key, pending);

        block.setBlockData(Material.AIR.createBlockData(), false);
        block.getWorld().playSound(block.getLocation(), sound, SoundCategory.BLOCKS, 1.0f, 0.9f);

        pending.task = Bukkit.getScheduler().runTaskLater(plugin, () -> restore(key), delay);
        return true;
    }

    private boolean potion(Player player, PrankBlock entry, BlocksConfig config) {
        PotionSpec spec = potionFor(entry, config);
        if (spec == null) {
            return false;
        }
        player.addPotionEffect(new PotionEffect(spec.type(), spec.durationTicks(), spec.amplifier(),
                false, true, true));
        actionBar(player, Component.text("Pranked: ", NamedTextColor.GRAY)
                .append(Component.text(spec.name(), NamedTextColor.RED)), HudChannel.PRIORITY_POINTS);
        player.playSound(player.getLocation(), Sound.ENTITY_WITCH_DRINK, SoundCategory.PLAYERS, 0.8f, 1.0f);
        return true;
    }

    private PotionSpec potionFor(PrankBlock entry, BlocksConfig config) {
        if (entry.effect() != null) {
            PotionEffectType type = BlocksConfig.resolveEffect(entry.effect(), plugin.getLogger());
            if (type != null) {
                int duration = entry.durationSeconds() != null ? entry.durationSeconds() * 20 : 5 * 20;
                int amplifier = entry.amplifier() != null ? entry.amplifier() : 0;
                return new PotionSpec(type, duration, amplifier);
            }
            plugin.getLogger().warning("Unknown per-block potion effect '" + entry.effect()
                    + "' at " + entry.key() + ", using the configured list.");
        }
        List<PotionSpec> effects = config.potionEffects();
        if (effects.isEmpty()) {
            return null;
        }
        return effects.get(ThreadLocalRandom.current().nextInt(effects.size()));
    }

    private boolean swap(Player victim, BlocksConfig config) {
        Location origin = victim.getLocation();
        double radius = config.swapExcludeRadius();
        double radiusSquared = radius * radius;
        List<Player> candidates = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(victim.getUniqueId())) {
                continue;
            }
            if (!other.getWorld().equals(victim.getWorld())) {
                continue;
            }
            if (isExcluded(other)) {
                continue;
            }
            if (other.getLocation().distanceSquared(origin) <= radiusSquared) {
                continue;
            }
            if (!isSafeLanding(other.getLocation())) {
                continue;
            }
            candidates.add(other);
        }

        if (candidates.isEmpty()) {
            actionBar(victim, Component.text("Swap block: no safe player to swap with.",
                    NamedTextColor.GRAY), HudChannel.PRIORITY_CRITICAL);
            return false;
        }

        if (!isSafeLanding(origin)) {
            actionBar(victim, Component.text("Swap block: your own spot is not safe to send someone to.",
                    NamedTextColor.GRAY), HudChannel.PRIORITY_CRITICAL);
            return false;
        }

        Player target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        Location targetOrigin = target.getLocation().clone();
        victim.teleport(targetOrigin);
        victim.setFallDistance(0.0f);
        target.teleport(origin);
        target.setFallDistance(0.0f);
        swapCooldowns.start(victim.getUniqueId(), config.swapCooldownMillis());
        victim.playSound(victim.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT,
                SoundCategory.PLAYERS, 1.0f, 1.0f);
        target.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT,
                SoundCategory.PLAYERS, 0.7f, 1.2f);
        actionBar(victim, Component.text("Swap block: hello, " + target.getName() + "!",
                NamedTextColor.LIGHT_PURPLE), HudChannel.PRIORITY_CRITICAL);
        actionBar(target, Component.text("You were swapped with " + victim.getName() + "!",
                NamedTextColor.LIGHT_PURPLE), HudChannel.PRIORITY_CRITICAL);
        return true;
    }

    /** Prank feedback shares the action bar with points/darts instead of stomping it. */
    private void actionBar(Player player, Component message, int priority) {
        HudChannel channel = Bukkit.getServicesManager().load(HudChannel.class);
        if (channel != null) {
            channel.send(player, message, priority, 40);
            return;
        }
        player.sendActionBar(message);
    }

    /** Rejects targets standing in lava, fire, the void, or inside a block. */
    static boolean isSafeLanding(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        World world = location.getWorld();
        if (!Double.isFinite(location.getY())
                || location.getY() < world.getMinHeight() + 1.0
                || location.getY() >= world.getMaxHeight()) {
            return false;
        }
        Block feet = location.getBlock();
        Block below = feet.getRelative(BlockFace.DOWN);
        Block head = feet.getRelative(BlockFace.UP);
        if (feet.isLiquid() || head.isLiquid() || below.isLiquid()) {
            return false;
        }
        if (!below.getType().isSolid()) {
            return false;
        }
        if (feet.getType().isSolid() || head.getType().isSolid()) {
            return false;
        }
        return !hazard(below.getType()) && !hazard(feet.getType()) && !hazard(head.getType());
    }

    private static boolean hazard(Material type) {
        return switch (type) {
            case LAVA, MAGMA_BLOCK, FIRE, SOUL_FIRE, CAMPFIRE, SOUL_CAMPFIRE,
                 CACTUS, SWEET_BERRY_BUSH, POWDER_SNOW -> true;
            default -> false;
        };
    }

    private boolean restore(String key) {
        PendingRestore pending = pendingRestores.remove(key);
        if (pending == null) {
            return false;
        }
        if (pending.task != null) {
            pending.task.cancel();
        }
        World world = Bukkit.getWorld(pending.world);
        if (world == null) {
            plugin.getLogger().warning("Could not restore fake block at " + key + ": world is not loaded.");
            return false;
        }
        Block block = world.getBlockAt(pending.x, pending.y, pending.z);
        if (block.getType() != Material.AIR) {
            plugin.getLogger().warning("Skipped restoring fake block at " + key
                    + ": the block was replaced by " + block.getType() + ".");
            return false;
        }
        block.setBlockData(pending.data, false);
        return true;
    }

    public record PendingRestoreInfo(String world, int x, int y, int z, String original) {
    }

    private static final class PendingRestore {

        private final String world;
        private final int x;
        private final int y;
        private final int z;
        private final BlockData data;
        private BukkitTask task;

        private PendingRestore(String world, int x, int y, int z, BlockData data) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.data = data;
        }
    }
}
