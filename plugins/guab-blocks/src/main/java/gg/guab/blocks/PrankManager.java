package gg.guab.blocks;

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
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class PrankManager {

    private final GuabBlocksPlugin plugin;
    private final Map<UUID, Long> playerCooldowns = new HashMap<>();
    private final Map<String, Long> blockCooldowns = new HashMap<>();
    private final Map<UUID, Long> swapCooldowns = new HashMap<>();
    private final Map<String, PendingRestore> pendingRestores = new HashMap<>();

    public PrankManager(GuabBlocksPlugin plugin) {
        this.plugin = plugin;
    }

    public void trigger(Player player, Block block, PrankBlock entry) {
        BlocksConfig config = plugin.config();
        long now = System.currentTimeMillis();

        if (now - playerCooldowns.getOrDefault(player.getUniqueId(), 0L) < config.perPlayerCooldownMillis()) {
            return;
        }
        long blockCooldown = entry.cooldownSeconds() != null
                ? entry.cooldownSeconds() * 1000L
                : config.perBlockCooldownMillis();
        if (now - blockCooldowns.getOrDefault(entry.key(), 0L) < blockCooldown) {
            return;
        }
        if (entry.type() == PrankType.SWAP
                && now - swapCooldowns.getOrDefault(player.getUniqueId(), 0L) < config.swapCooldownMillis()) {
            return;
        }

        playerCooldowns.put(player.getUniqueId(), now);
        blockCooldowns.put(entry.key(), now);

        switch (entry.type()) {
            case FAKE -> fake(block, entry, config);
            case POTION -> potion(player, entry, config);
            case SWAP -> swap(player, config);
        }
    }

    public void forget(Player player) {
        UUID id = player.getUniqueId();
        playerCooldowns.remove(id);
        swapCooldowns.remove(id);
    }

    public void restoreAll() {
        for (String key : List.copyOf(pendingRestores.keySet())) {
            restore(key);
        }
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

    private void fake(Block block, PrankBlock entry, BlocksConfig config) {
        String key = entry.key();
        if (pendingRestores.containsKey(key)) {
            return;
        }
        double chance = entry.chance() != null ? entry.chance() : config.fakeChance();
        if (chance <= 0.0 || ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }

        BlockData original = block.getBlockData().clone();
        int delay = entry.restoreDelayTicks() != null
                ? entry.restoreDelayTicks()
                : config.fakeRestoreDelayTicks();
        Sound sound = entry.breakSound() != null
                ? BlocksConfig.resolveSound(entry.breakSound(), config.fakeBreakSound())
                : config.fakeBreakSound();

        PendingRestore pending = new PendingRestore(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), original);
        pendingRestores.put(key, pending);

        block.setBlockData(Material.AIR.createBlockData(), false);
        block.getWorld().playSound(block.getLocation(), sound, SoundCategory.BLOCKS, 1.0f, 0.9f);

        pending.task = Bukkit.getScheduler().runTaskLater(plugin, () -> restore(key), delay);
    }

    private void potion(Player player, PrankBlock entry, BlocksConfig config) {
        PotionSpec spec = potionFor(entry, config);
        if (spec == null) {
            return;
        }
        player.addPotionEffect(new PotionEffect(spec.type(), spec.durationTicks(), spec.amplifier(),
                false, true, true));
        player.sendActionBar(Component.text("Pranked: ", NamedTextColor.GRAY)
                .append(Component.text(title(spec.name()), NamedTextColor.RED)));
        player.playSound(player.getLocation(), Sound.ENTITY_WITCH_DRINK, SoundCategory.PLAYERS, 0.8f, 1.0f);
    }

    private PotionSpec potionFor(PrankBlock entry, BlocksConfig config) {
        if (entry.effect() != null) {
            PotionEffectType type = BlocksConfig.resolveEffect(entry.effect());
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

    private void swap(Player victim, BlocksConfig config) {
        swapCooldowns.put(victim.getUniqueId(), System.currentTimeMillis());

        Location origin = victim.getLocation();
        double radius = config.swapExcludeRadius();
        double radiusSquared = radius * radius;
        List<Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        List<Player> candidates = online.stream()
                .filter(other -> !other.getUniqueId().equals(victim.getUniqueId()))
                .filter(other -> other.getWorld().equals(victim.getWorld()))
                .filter(other -> other.getGameMode() != GameMode.SPECTATOR)
                .filter(other -> other.getLocation().distanceSquared(origin) > radiusSquared)
                .toList();

        if (candidates.isEmpty()) {
            victim.sendActionBar(Component.text("Swap block: no one to swap with.", NamedTextColor.GRAY));
            return;
        }

        Player target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        victim.teleport(target.getLocation());
        victim.setFallDistance(0.0f);
        victim.playSound(victim.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);
        target.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.7f, 1.2f);
        victim.sendActionBar(Component.text("Swap block: hello, " + target.getName() + "!",
                NamedTextColor.LIGHT_PURPLE));
    }

    private void restore(String key) {
        PendingRestore pending = pendingRestores.remove(key);
        if (pending == null) {
            return;
        }
        if (pending.task != null) {
            pending.task.cancel();
        }
        World world = Bukkit.getWorld(pending.world);
        if (world == null) {
            plugin.getLogger().warning("Could not restore fake block at " + key + ": world is not loaded.");
            return;
        }
        world.getBlockAt(pending.x, pending.y, pending.z).setBlockData(pending.data, false);
    }

    private String title(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
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
