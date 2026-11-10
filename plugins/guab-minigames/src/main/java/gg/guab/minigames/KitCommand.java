package gg.guab.minigames;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import gg.guab.core.HudChannel;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Gives the battle pit PvP kit, with a short cooldown. */
public final class KitCommand implements CommandExecutor, Listener {

    private final GuabMinigamesPlugin plugin;
    private final BattlePit pit;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public KitCommand(GuabMinigamesPlugin plugin, BattlePit pit) {
        this.plugin = plugin;
        this.pit = pit;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can take a kit.");
            return true;
        }
        if (!plugin.getConfig().getBoolean("kit.enabled", true)) {
            error(sender, "The battle pit kit is disabled.");
            return true;
        }
        if (!player.hasPermission("guab.kit.use")) {
            error(sender, "You are not allowed to take a kit.");
            return true;
        }
        if (player.isDead() || player.getHealth() <= 0.0) {
            error(sender, "You cannot take a kit while dead.");
            return true;
        }
        if (plugin.getConfig().getBoolean("kit.require-battlepit", true) && !pit.inPit(player)) {
            error(sender, "Join the battle pit first: /battlepit join");
            return true;
        }

        long cooldown = Math.max(0, plugin.getConfig().getLong("kit.cooldown-seconds", 30)) * 1000L;
        long now = System.currentTimeMillis();
        long remaining = cooldowns.getOrDefault(player.getUniqueId(), 0L) + cooldown - now;
        if (remaining > 0) {
            long seconds = (remaining + 999) / 1000;
            plugin.actionBar(player, Component.text("Kit ready in " + seconds + "s",
                    NamedTextColor.RED), HudChannel.PRIORITY_HINT, 40);
            error(sender, "Kit ready in " + seconds + "s.");
            return true;
        }
        cooldowns.put(player.getUniqueId(), now);

        if (plugin.getConfig().getBoolean("kit.clear-inventory", true)) {
            BattleKit.resetAndGive(plugin, player);
        } else {
            BattleKit.give(plugin, player);
        }
        plugin.actionBar(player, Component.text("Kit equipped", NamedTextColor.GREEN),
                HudChannel.PRIORITY_HINT, 40);
        player.sendMessage(Component.text("[Kit] ", NamedTextColor.GOLD)
                .append(Component.text("Battle pit PvP kit equipped.", NamedTextColor.WHITE)));
        return true;
    }

    /** Called when a fighter joins or leaves so a fresh session starts ready. */
    void resetCooldown(UUID id) {
        cooldowns.remove(id);
    }

    void forget(UUID id) {
        cooldowns.remove(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Kit] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.RED)));
    }
}
