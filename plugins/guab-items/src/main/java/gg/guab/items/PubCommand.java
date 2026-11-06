package gg.guab.items;

import gg.guab.core.PointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PubCommand implements CommandExecutor, TabCompleter {

    private final GuabItemsPlugin plugin;
    private final Map<UUID, Long> roundCooldowns = new HashMap<>();

    public PubCommand(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "round" -> round(sender);
            case "pints" -> pints(sender, args);
            case "quiz" -> quiz(sender);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void round(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can buy a round.");
            return;
        }
        PubManager pub = plugin.pub();
        Location centre = pub.centre();
        if (centre == null) {
            error(sender, "The pub is not configured.");
            return;
        }
        double radius = Math.max(2.0, plugin.getConfig().getDouble("pub.round.radius", 14.0));
        if (!player.getWorld().equals(centre.getWorld())
                || player.getLocation().distanceSquared(centre) > radius * radius) {
            error(sender, "Stand in the pub to buy a round.");
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, plugin.getConfig().getInt("pub.round.cooldown-seconds", 30)) * 1000L;
        if (now - roundCooldowns.getOrDefault(player.getUniqueId(), 0L) < cooldown) {
            error(sender, "The bar is still pouring your last round.");
            return;
        }

        List<Player> drinkers = new ArrayList<>();
        for (Player nearby : centre.getWorld().getPlayers()) {
            if (nearby.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            if (nearby.getLocation().distanceSquared(centre) <= radius * radius) {
                drinkers.add(nearby);
            }
        }
        if (drinkers.size() < 2) {
            error(sender, "You need at least one other person in the pub to buy a round.");
            return;
        }

        int costEach = Math.max(0, plugin.getConfig().getInt("pub.round.cost-per-pint", 1));
        int total = costEach * drinkers.size();
        PointsService points = points();
        if (total > 0) {
            if (points == null || !points.take(player.getUniqueId(), total)) {
                error(sender, "A round costs " + total + " points and you cannot cover it.");
                return;
            }
        }
        roundCooldowns.put(player.getUniqueId(), now);

        for (Player drinker : drinkers) {
            var beer = plugin.items().create(ItemType.BEER, 1);
            drinker.getInventory().addItem(beer).values()
                    .forEach(leftover -> drinker.getWorld().dropItemNaturally(drinker.getLocation(), leftover));
            drinker.playSound(drinker.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.1f);
            drinker.sendMessage(Component.text("[Spoons] ", NamedTextColor.GOLD)
                    .append(Component.text(player.getName() + " bought the round — get it down you.",
                            NamedTextColor.YELLOW)));
        }
    }

    private void pints(CommandSender sender, String[] args) {
        PubManager pub = plugin.pub();
        if (sender instanceof Player player) {
            info(sender, "You have drunk " + pub.pints(player.getUniqueId()) + " pints.");
        }
        int limit = args.length > 0 && args[0].equalsIgnoreCase("top") ? 10 : 3;
        List<Map.Entry<String, Integer>> top = pub.top(limit);
        if (top.isEmpty()) {
            info(sender, "Nobody has drunk a pint yet. The bar is right there.");
            return;
        }
        info(sender, "Pints drunk:");
        for (int i = 0; i < top.size(); i++) {
            sender.sendMessage(Component.text("  " + (i + 1) + ". ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(top.get(i).getKey(), NamedTextColor.WHITE))
                    .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(top.get(i).getValue(), NamedTextColor.GOLD)));
        }
    }

    private void quiz(CommandSender sender) {
        if (!sender.hasPermission("guab.pub.quiz.start")) {
            error(sender, "You need guab.pub.quiz.start to start the quiz.");
            return;
        }
        plugin.quiz().begin(sender);
    }

    private PointsService points() {
        var registration = Bukkit.getServicesManager().getRegistration(PointsService.class);
        return registration == null ? null : registration.getProvider();
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Spoons] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Spoons] ", NamedTextColor.RED)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("pints") && args.length == 1) {
            return List.of("top");
        }
        return List.of();
    }
}
