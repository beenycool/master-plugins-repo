package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A dartboard block in the pub. Right-click to throw; the score is pure luck.
 */
public final class DartsBoard implements Listener {

    private final GuabItemsPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Integer> best = new HashMap<>();

    public DartsBoard(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !isBoard(block)) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, plugin.getConfig().getInt("pub.darts.cooldown-seconds", 2)) * 1000L;
        if (now - cooldowns.getOrDefault(player.getUniqueId(), 0L) < cooldown) {
            return;
        }
        cooldowns.put(player.getUniqueId(), now);

        int score = roll();
        int previousBest = best.getOrDefault(player.getUniqueId(), 0);
        if (score > previousBest) {
            best.put(player.getUniqueId(), score);
            previousBest = score;
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ARROW_HIT, 1.0f, 1.2f);
        player.sendMessage(Component.text("[Darts] ", NamedTextColor.GOLD)
                .append(Component.text(describe(score), NamedTextColor.WHITE))
                .append(Component.text("  (best " + previousBest + ")", NamedTextColor.GRAY)));
    }

    private int roll() {
        int chance = ThreadLocalRandom.current().nextInt(100);
        if (chance < 5) {
            return 0;
        }
        if (chance < 10) {
            return 50;
        }
        if (chance < 20) {
            return 25;
        }
        return 1 + ThreadLocalRandom.current().nextInt(20);
    }

    private String describe(int score) {
        return switch (score) {
            case 0 -> "Missed the board.";
            case 50 -> "Bullseye! 50.";
            case 25 -> "Outer bull, 25.";
            default -> "Scored " + score + ".";
        };
    }

    private boolean isBoard(Block block) {
        String world = plugin.getConfig().getString("pub.darts.board.world", "guab_casino");
        int x = plugin.getConfig().getInt("pub.darts.board.x", -24);
        int y = plugin.getConfig().getInt("pub.darts.board.y", 66);
        int z = plugin.getConfig().getInt("pub.darts.board.z", -12);
        return block.getWorld().getName().equals(world)
                && block.getX() == x
                && block.getY() == y
                && block.getZ() == z;
    }
}
