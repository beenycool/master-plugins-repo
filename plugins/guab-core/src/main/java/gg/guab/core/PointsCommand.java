package gg.guab.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PointsCommand implements CommandExecutor, TabCompleter, Listener {

    private static final long CONFIRM_MILLIS = 30_000L;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final GuabCorePlugin plugin;
    private final PlayerResolver resolver;
    private final Map<UUID, Long> sessionDeltas = new ConcurrentHashMap<>();
    private final PointsHistoryLog history = new PointsHistoryLog();
    private final Map<String, Confirmation> confirmations = new ConcurrentHashMap<>();

    public PointsCommand(GuabCorePlugin plugin) {
        this.plugin = plugin;
        this.resolver = new PlayerResolver(plugin.points(), plugin.stats());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("top")) {
            String[] topArgs = new String[args.length + 1];
            topArgs[0] = "top";
            System.arraycopy(args, 0, topArgs, 1, args.length);
            return top(sender, topArgs);
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                error(sender, "Run this in game, or use /points <player>.");
                return true;
            }
            sendOwnBalance(player);
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        return switch (first) {
            case "top", "leaderboard" -> top(sender, args);
            case "history", "recent" -> history(sender, args);
            case "pay", "transfer" -> pay(sender, args);
            case "reset" -> reset(sender, args);
            case "set", "add", "take" -> mutate(sender, first, args);
            default -> otherBalance(sender, args[0]);
        };
    }

    private void sendOwnBalance(Player player) {
        UUID id = player.getUniqueId();
        long balance = plugin.points().get(id);
        int rank = plugin.points().rank(id);
        long session = sessionDeltas.getOrDefault(id, 0L);
        Component message = Component.text("[Points] ", NamedTextColor.GOLD)
                .append(Component.text("Your balance: ", NamedTextColor.GRAY))
                .append(Component.text(formatNumber(balance), NamedTextColor.GOLD));
        if (rank > 0) {
            message = message.append(Component.text(" · rank #" + rank, NamedTextColor.GRAY));
        } else {
            message = message.append(Component.text(" · unranked", NamedTextColor.DARK_GRAY));
        }
        message = message.append(Component.text(" · session " + signed(session),
                session > 0 ? NamedTextColor.GREEN
                        : session < 0 ? NamedTextColor.RED : NamedTextColor.GRAY));
        player.sendMessage(message);
    }

    private boolean otherBalance(CommandSender sender, String name) {
        if (!sender.hasPermission("guab.points.others")) {
            error(sender, "You need guab.points.others to check another player.");
            return true;
        }
        PlayerResolver.Match target = resolver.resolve(name);
        if (target == null) {
            error(sender, "Unknown player: " + name + " (only online or known accounts work).");
            return true;
        }
        long balance = plugin.points().get(target.id());
        int rank = plugin.points().rank(target.id());
        String suffix = rank > 0 ? " (rank #" + rank + ")" : "";
        info(sender, target.displayName() + " has " + formatNumber(balance) + " points" + suffix + ".");
        return true;
    }

    private boolean mutate(CommandSender sender, String action, String[] args) {
        if (!sender.hasPermission("guab.points.admin")) {
            error(sender, "You need guab.points.admin to change balances.");
            return true;
        }
        if (args.length != 3) {
            error(sender, "Usage: /points " + action + " <player> <amount>");
            return true;
        }
        PlayerResolver.Match target = resolver.resolve(args[1]);
        if (target == null) {
            error(sender, "Unknown player: " + args[1]);
            return true;
        }
        Long requested = parseAmount(args[2]);
        if (requested == null) {
            error(sender, "Amount must be a whole number.");
            return true;
        }
        long amount = requested;
        if (amount < 0) {
            error(sender, "Amount must be zero or more.");
            return true;
        }
        if (action.equals("take") && amount <= 0) {
            error(sender, "Amount must be at least 1.");
            return true;
        }

        long before = plugin.points().get(target.id());
        long after;
        switch (action) {
            case "set" -> {
                plugin.points().set(target.id(), amount);
                after = plugin.points().get(target.id());
            }
            case "add" -> after = plugin.points().add(target.id(), amount);
            case "take" -> {
                if (!plugin.points().take(target.id(), amount)) {
                    error(sender, target.displayName() + " only has "
                            + formatNumber(before) + " points.");
                    return true;
                }
                after = plugin.points().get(target.id());
            }
            default -> {
                return true;
            }
        }
        long delta = after - before;
        audit(sender, action + " " + target.displayName() + " " + formatNumber(amount)
                + " (" + formatNumber(before) + " -> " + formatNumber(after) + ")");
        info(sender, target.displayName() + ": " + formatNumber(before) + " -> "
                + formatNumber(after) + " points (" + signed(delta) + ").");
        notifyTarget(target, "Your balance was changed by " + sender.getName()
                + ": " + formatNumber(before) + " -> " + formatNumber(after) + " points.");
        return true;
    }

    private boolean pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Run this in game to pay another player.");
            return true;
        }
        if (!player.hasPermission("guab.points.pay")) {
            error(sender, "You need guab.points.pay to send points.");
            return true;
        }
        if (args.length != 3) {
            error(sender, "Usage: /points pay <player> <amount>");
            return true;
        }
        PlayerResolver.Match target = resolver.resolve(args[1]);
        if (target == null) {
            error(sender, "Unknown player: " + args[1] + " (only online or known accounts work).");
            return true;
        }
        if (target.id().equals(player.getUniqueId())) {
            error(sender, "You cannot pay yourself.");
            return true;
        }
        Long requested = parseAmount(args[2]);
        if (requested == null || requested <= 0) {
            error(sender, "Amount must be at least 1 point.");
            return true;
        }
        long before = plugin.points().get(player.getUniqueId());
        if (!plugin.points().transfer(player.getUniqueId(), target.id(), requested)) {
            error(sender, "You only have " + formatNumber(before) + " points.");
            return true;
        }
        long after = plugin.points().get(player.getUniqueId());
        audit(sender, "pay " + target.displayName() + " " + formatNumber(requested)
                + " (" + formatNumber(before) + " -> " + formatNumber(after) + ")");
        info(sender, "Paid " + target.displayName() + " " + formatNumber(requested)
                + " points. Your balance: " + formatNumber(after) + ".");
        notifyTarget(target, player.getName() + " paid you " + formatNumber(requested)
                + " points.");
        return true;
    }

    private boolean history(CommandSender sender, String[] args) {
        UUID targetId;
        String targetName;
        if (args.length >= 2) {
            if (!sender.hasPermission("guab.points.others")) {
                error(sender, "You need guab.points.others to check another player's history.");
                return true;
            }
            PlayerResolver.Match target = resolver.resolve(args[1]);
            if (target == null) {
                error(sender, "Unknown player: " + args[1]);
                return true;
            }
            targetId = target.id();
            targetName = target.displayName();
        } else if (sender instanceof Player player) {
            targetId = player.getUniqueId();
            targetName = player.getName();
        } else {
            error(sender, "Run this in game, or use /points history <player>.");
            return true;
        }

        List<PointsHistoryLog.Change> snapshot = history.recent(targetId);
        if (snapshot.isEmpty()) {
            info(sender, "No recent point changes for " + targetName + ".");
            return true;
        }
        info(sender, "Recent points changes for " + targetName + " (newest first):");
        for (PointsHistoryLog.Change change : snapshot) {
            sender.sendMessage(Component.text("  " + signed(change.delta()), change.delta() >= 0
                                    ? NamedTextColor.GREEN : NamedTextColor.RED)
                    .append(Component.text("  " + formatNumber(change.before()) + " -> "
                            + formatNumber(change.after()), NamedTextColor.GRAY))
                    .append(Component.text("  " + TIME_FORMAT.format(change.at()),
                            NamedTextColor.DARK_GRAY)));
        }
        return true;
    }

    private boolean reset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("guab.points.admin")) {
            error(sender, "You need guab.points.admin to reset balances.");
            return true;
        }
        if (args.length < 2) {
            error(sender, "Usage: /points reset <player|all>");
            return true;
        }
        String actor = actor(sender);
        String targetArg = args[1];
        boolean confirm = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
        if (args.length >= 3 && args[2].equalsIgnoreCase("cancel")) {
            confirmations.remove(actor);
            info(sender, "Reset cancelled.");
            return true;
        }
        boolean all = targetArg.equalsIgnoreCase("all");
        PlayerResolver.Match target = null;
        if (!all) {
            target = resolver.resolve(targetArg);
            if (target == null) {
                error(sender, "Unknown player: " + targetArg);
                return true;
            }
        }
        String key = all ? "*" : target.id().toString();
        if (!confirm) {
            confirmations.put(actor, new Confirmation(key,
                    System.currentTimeMillis() + CONFIRM_MILLIS));
            if (all) {
                info(sender, "Reset every known balance to zero? This cannot be undone.");
            } else {
                info(sender, "Reset " + target.displayName() + " from "
                        + formatNumber(plugin.points().get(target.id())) + " to 0 points?");
            }
            String commandTarget = all ? "all" : target.id().toString();
            sender.sendMessage(Component.text("  [Confirm]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/points reset " + commandTarget + " confirm"))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            "Click to reset", NamedTextColor.GRAY)))
                    .append(Component.text("  "))
                    .append(Component.text("[Cancel]", NamedTextColor.RED)
                            .clickEvent(ClickEvent.runCommand(
                                    "/points reset " + commandTarget + " cancel"))
                            .hoverEvent(HoverEvent.showText(Component.text(
                                    "Click to cancel", NamedTextColor.GRAY)))));
            sender.sendMessage(Component.text("  Confirmation expires in 30 seconds.",
                    NamedTextColor.DARK_GRAY));
            return true;
        }

        Confirmation pending = confirmations.remove(actor);
        if (pending == null || !pending.key().equals(key)
                || pending.expiresAt() < System.currentTimeMillis()) {
            error(sender, "That reset confirmation expired. Run the command again.");
            return true;
        }
        if (all) {
            int count = 0;
            for (UUID id : plugin.points().knownPlayers()) {
                plugin.points().set(id, 0);
                count++;
            }
            audit(sender, "reset-all " + count + " account(s)");
            info(sender, "Reset " + count + " known balance(s) to zero.");
        } else {
            long before = plugin.points().get(target.id());
            plugin.points().set(target.id(), 0);
            audit(sender, "reset " + target.displayName() + " ("
                    + formatNumber(before) + " -> 0)");
            info(sender, "Reset " + target.displayName() + " from "
                    + formatNumber(before) + " to 0 points.");
        }
        return true;
    }

    private boolean top(CommandSender sender, String[] args) {
        int limit = 5;
        if (args.length >= 2) {
            Long parsed = parseAmount(args[1]);
            if (parsed == null) {
                error(sender, "Usage: /points top [count]");
                return true;
            }
            limit = (int) Math.max(1L, Math.min(20L, parsed));
        }
        limit = Math.max(1, Math.min(20, limit));
        List<Map.Entry<UUID, Long>> ranking = plugin.points().top(limit);
        if (ranking.isEmpty()) {
            info(sender, "No points have been earned yet.");
            return true;
        }
        info(sender, "Top " + ranking.size() + " balances:");
        int rank = 1;
        for (Map.Entry<UUID, Long> entry : ranking) {
            String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
            if (name == null) {
                name = entry.getKey().toString().substring(0, 8);
            }
            sender.sendMessage(Component.text("  #" + rank + " ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(formatNumber(entry.getValue()), NamedTextColor.GOLD)));
            rank++;
        }
        return true;
    }

    private void notifyTarget(PlayerResolver.Match target, String message) {
        if (target.online()) {
            Player player = Bukkit.getPlayer(target.id());
            if (player != null) {
                player.sendMessage(Component.text("[Points] ", NamedTextColor.GOLD)
                        .append(Component.text(message, NamedTextColor.WHITE)));
            }
        }
    }

    private void audit(CommandSender sender, String detail) {
        plugin.getLogger().info("[audit] points " + sender.getName() + ": " + detail);
    }

    private static Long parseAmount(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String formatNumber(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String signed(long value) {
        if (value > 0) {
            return "+" + formatNumber(value);
        }
        return formatNumber(value);
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player
                ? player.getUniqueId().toString()
                : "console:" + sender.getName();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChange(GuabPointsChangeEvent event) {
        UUID id = event.player();
        sessionDeltas.merge(id, event.delta(), Long::sum);
        history.record(id, event.before(), event.after(), Instant.now());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sessionDeltas.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessionDeltas.remove(event.getPlayer().getUniqueId());
        confirmations.remove(event.getPlayer().getUniqueId().toString());
    }

    private record Confirmation(String key, long expiresAt) {
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Points] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Points] ", NamedTextColor.RED)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        if (command.getName().equalsIgnoreCase("top")) {
            if (args.length == 1) {
                return filtered(List.of("5", "10", "20", "50"), args[0]);
            }
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("top", "history"));
            if (sender instanceof Player && sender.hasPermission("guab.points.pay")) {
                out.add("pay");
            }
            if (sender.hasPermission("guab.points.admin")) {
                out.addAll(List.of("set", "add", "take", "reset"));
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                out.add(player.getName());
            }
        } else if (args.length == 2) {
            String first = args[0].toLowerCase(Locale.ROOT);
            if (first.equals("top") || first.equals("leaderboard")) {
                out.addAll(List.of("5", "10", "20", "50"));
            } else if (first.equals("reset")) {
                out.add("all");
                for (Player player : Bukkit.getOnlinePlayers()) {
                    out.add(player.getName());
                }
            } else if (first.equals("history") || first.equals("pay")
                    || first.equals("set") || first.equals("add") || first.equals("take")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    out.add(player.getName());
                }
            }
        } else if (args.length == 3) {
            String first = args[0].toLowerCase(Locale.ROOT);
            if (first.equals("reset")) {
                out.addAll(List.of("confirm", "cancel"));
            } else if (first.equals("pay") || first.equals("set")
                    || first.equals("add") || first.equals("take")) {
                out.addAll(List.of("10", "50", "100", "500"));
            }
        }
        return filtered(out, args[args.length - 1]);
    }

    private static List<String> filtered(List<String> values, String prefixRaw) {
        String prefix = prefixRaw.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .distinct()
                .toList();
    }
}
