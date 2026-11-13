package gg.guab.items;

import gg.guab.core.CooldownMap;
import gg.guab.core.PointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class PubCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "guab.pub.use";
    private static final String QUIZ_ADMIN = "guab.pub.quiz.admin";
    private static final String PUB_ADMIN = "guab.pub.admin";

    private final GuabItemsPlugin plugin;
    private final CooldownMap<UUID> roundCooldowns = new CooldownMap<>();
    private final Map<UUID, PendingRound> pendingRounds = new HashMap<>();

    public PubCommand(GuabItemsPlugin plugin) {
        this.plugin = plugin;
        // /pub is optional in plugin.yml; bind it here so the happy-hour toggle
        // works as soon as the command is declared, without another wiring edit.
        PluginCommand pubCommand = plugin.getCommand("pub");
        if (pubCommand != null) {
            pubCommand.setExecutor(this);
            pubCommand.setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "round" -> round(sender, args);
            case "pints" -> pints(sender, args);
            case "quiz" -> quiz(sender, args);
            case "beer" -> beer(sender, args);
            case "pub" -> pub(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // /round
    // ------------------------------------------------------------------

    private void round(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can buy a round.");
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("confirm")) {
            confirmRound(player);
            return;
        }
        if (sub.equals("cancel")) {
            cancelRound(player);
            return;
        }
        purgeExpiredRounds();

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
        long remaining = roundCooldownRemaining(player);
        if (remaining > 0L) {
            error(sender, "The bar is still pouring your last round. "
                    + seconds(remaining) + "s to go.");
            return;
        }

        BeerBrand brand = args.length > 0 ? plugin.beer().brandFor(args[0]) : plugin.beer().houseBrand();
        if (brand == null) {
            error(sender, "Unknown beer: " + args[0] + ". Try /beer list.");
            return;
        }
        BeerStyle style = brand.style();
        List<Player> drinkers = nearbyDrinkers(centre, radius);
        if (drinkers.size() < 2) {
            error(sender, "You need at least one other person in the pub to buy a round.");
            return;
        }

        int costEach = args.length > 0
                ? pub.pubPrice(Math.max(0, style.price()))
                : pub.pubPrice(Math.max(0, plugin.getConfig().getInt("pub.round.cost-per-pint", 1)));
        long total = (long) costEach * drinkers.size();
        long expiresAt = System.currentTimeMillis() + confirmSeconds() * 1000L;
        pendingRounds.put(player.getUniqueId(), new PendingRound(
                brand.id(), drinkers.stream().map(Player::getUniqueId).toList(), costEach, expiresAt));

        Component preview = prefix()
                .append(Component.text("Round of " + brand.displayName() + " for "
                        + drinkers.size() + " drinkers: " + total
                        + (total == 1 ? " point. " : " points. "), NamedTextColor.GOLD))
                .append(Component.text("[Confirm]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/round confirm"))
                        .hoverEvent(HoverEvent.showText(Component.text("Charge the till and pour."))))
                .append(Component.text("  "))
                .append(Component.text("[Cancel]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/round cancel"))
                        .hoverEvent(HoverEvent.showText(Component.text("Do not buy this round."))))
                .append(Component.text("  (" + confirmSeconds() + "s)", NamedTextColor.DARK_GRAY));
        player.sendMessage(preview);
    }

    private void confirmRound(Player player) {
        PendingRound pending = pendingRounds.get(player.getUniqueId());
        if (pending == null) {
            error(player, "No round is waiting. Start one with /round.");
            return;
        }
        if (pending.expiresAt() < System.currentTimeMillis()) {
            pendingRounds.remove(player.getUniqueId());
            error(player, "That round preview expired. Run /round again.");
            return;
        }
        long remaining = roundCooldownRemaining(player);
        if (remaining > 0L) {
            error(player, "The bar is still pouring your last round. "
                    + seconds(remaining) + "s to go.");
            return;
        }
        pendingRounds.remove(player.getUniqueId());

        BeerBrand brand = plugin.beer().findBrand(pending.brandId());
        if (brand == null) {
            brand = plugin.beer().brandFor(pending.brandId());
        }
        if (brand == null) {
            error(player, "That beer is off the menu now.");
            return;
        }
        List<Player> drinkers = pending.drinkers().stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .filter(Player::isOnline)
                .filter(drinker -> drinker.hasPermission(PERMISSION))
                .filter(drinker -> drinker.getGameMode() != GameMode.SPECTATOR)
                .toList();
        if (drinkers.isEmpty()) {
            error(player, "Everyone has left the pub. The round was not charged.");
            return;
        }

        long total = (long) pending.costEach() * drinkers.size();
        PointsService points = points();
        if (total > 0) {
            if (points == null || !points.take(player.getUniqueId(), total)) {
                error(player, "A round costs " + total + " points and you cannot cover it.");
                return;
            }
        }
        roundCooldowns.start(player.getUniqueId(), roundCooldownMillis());

        for (Player drinker : drinkers) {
            plugin.beerService().give(drinker, brand, 1);
            GuabSounds.play(drinker, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.8f, 1.1f);
            drinker.sendMessage(Component.text("[Spoons] ", NamedTextColor.GOLD)
                    .append(Component.text(player.getName() + " bought the round — "
                            + brand.displayName() + " for everyone. Get it down you.",
                            NamedTextColor.YELLOW)));
        }
    }

    private void cancelRound(Player player) {
        if (pendingRounds.remove(player.getUniqueId()) == null) {
            info(player, "No round is waiting.");
            return;
        }
        info(player, "Round cancelled. No points were charged.");
    }

    private List<Player> nearbyDrinkers(Location centre, double radius) {
        List<Player> drinkers = new ArrayList<>();
        for (Player nearby : centre.getWorld().getPlayers()) {
            if (!nearby.hasPermission(PERMISSION)) {
                continue;
            }
            if (nearby.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (nearby.getLocation().distanceSquared(centre) <= radius * radius) {
                drinkers.add(nearby);
            }
        }
        return drinkers;
    }

    private long roundCooldownRemaining(Player player) {
        return roundCooldowns.remainingMillis(player.getUniqueId());
    }

    private long roundCooldownMillis() {
        return Math.max(0, plugin.getConfig().getInt("pub.round.cooldown-seconds", 30)) * 1000L;
    }

    private long confirmSeconds() {
        return Math.max(5, plugin.getConfig().getInt("pub.round.confirm-seconds", 30));
    }

    private void purgeExpiredRounds() {
        long now = System.currentTimeMillis();
        pendingRounds.values().removeIf(pending -> pending.expiresAt() < now);
    }

    // ------------------------------------------------------------------
    // /pints
    // ------------------------------------------------------------------

    private void pints(CommandSender sender, String[] args) {
        PubManager pub = plugin.pub();
        if (sender instanceof Player player) {
            double units = pub.drunkUnits(player.getUniqueId());
            info(sender, "You have drunk " + pub.pints(player.getUniqueId()) + " pints ("
                    + String.format(Locale.ROOT, "%.1f", units) + " units right now, "
                    + pub.tierLabel(units) + ").");
            topButtons(player);
        }
        String metric = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "top";
        List<Map.Entry<String, Integer>> top = metric.equals("units")
                ? pub.topUnits(10) : pub.top(10);
        if (top.isEmpty()) {
            info(sender, "Nobody has drunk a pint yet. The bar is right there.");
            return;
        }
        info(sender, metric.equals("units") ? "Units drunk:" : "Pints drunk:");
        for (int i = 0; i < top.size(); i++) {
            sender.sendMessage(Component.text("  " + (i + 1) + ". ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(top.get(i).getKey(), NamedTextColor.WHITE))
                    .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(top.get(i).getValue(), NamedTextColor.GOLD)));
        }
    }

    private void topButtons(Player player) {
        player.sendMessage(Component.text("[Top pints]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/pints top"))
                .hoverEvent(HoverEvent.showText(Component.text("Show the pint leaderboard.")))
                .append(Component.text("   "))
                .append(Component.text("[Top units]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/pints units"))
                        .hoverEvent(HoverEvent.showText(Component.text("Sort by lifetime units.")))));
    }

    // ------------------------------------------------------------------
    // /beer
    // ------------------------------------------------------------------

    private void beer(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (!(sender instanceof Player player)) {
                error(sender, "Only players can open the beer menu.");
                return;
            }
            plugin.beerMenu().open(player);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "stats", "record" -> {
                if (!(sender instanceof Player player)) {
                    error(sender, "Only players have a drinking record.");
                    return;
                }
                plugin.beerMenu().openStats(player);
            }
            case "milk" -> {
                if (!(sender instanceof Player player)) {
                    error(sender, "Only players can buy milk.");
                    return;
                }
                plugin.beerService().buyMilk(player, true);
            }
            case "again", "repeat" -> again(sender, args);
            case "buy", "pour" -> buy(sender, args);
            default -> error(sender, "Use /beer [menu|list|stats|milk|again [amount]|buy <beer> [amount]].");
        }
    }

    private void again(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can order another pint.");
            return;
        }
        Integer amount = null;
        if (args.length >= 2) {
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                error(sender, "Amount must be a number from 1 to 64.");
                return;
            }
            if (amount < 1 || amount > 64) {
                error(sender, "Amount must be a number from 1 to 64.");
                return;
            }
        }
        plugin.beerService().purchaseAgain(player, amount);
    }

    private void list(CommandSender sender) {
        info(sender, "The Wetherspoons menu:");
        if (plugin.pub().happyHourActive()) {
            info(sender, "Happy hour: pints are half price right now.");
        }
        for (BeerBrand brand : plugin.beer().allBrands()) {
            BeerStyle style = brand.style();
            int price = plugin.pub().pubPrice(style.price());
            Component line = Component.text("  " + brand.displayName(), style.colour())
                    .append(Component.text("  ·  " + style.displayName() + "  ·  "
                                    + String.format(Locale.ROOT, "%.1f", brand.abv()) + "%  ·  "
                                    + brand.unitsLabel() + " units  ·  " + price + " pt",
                            NamedTextColor.GRAY))
                    .append(style.lethal()
                            ? Component.text("  ☠", NamedTextColor.RED) : Component.empty());
            if (sender instanceof Player) {
                line = line.clickEvent(ClickEvent.runCommand("/beer buy " + brand.id()))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Buy " + brand.displayName() + " for " + price + " points.")));
            }
            sender.sendMessage(line);
        }
        if (plugin.getConfig().getBoolean("beer.milk.enabled", true)) {
            int milkCost = Math.max(0, plugin.getConfig().getInt("beer.milk.cost", 5));
            Component milk = Component.text("  Milk", NamedTextColor.WHITE)
                    .append(Component.text("  ·  sobers you up  ·  " + milkCost + " pt",
                            NamedTextColor.GRAY));
            if (sender instanceof Player) {
                milk = milk.clickEvent(ClickEvent.runCommand("/beer milk"))
                        .hoverEvent(HoverEvent.showText(Component.text("Buy a pint of milk.")));
            }
            sender.sendMessage(milk);
        }
    }

    private void buy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can buy a pint.");
            return;
        }
        if (args.length < 2) {
            error(sender, "Use /beer buy <beer> [amount]. Try /beer list.");
            return;
        }
        BeerBrand brand = plugin.beer().brandFor(args[1]);
        if (brand == null) {
            error(sender, "Unknown beer: " + args[1] + ". Try /beer list.");
            return;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.clamp(Integer.parseInt(args[2]), 1, 64);
            } catch (NumberFormatException exception) {
                error(sender, "Amount must be a number from 1 to 64.");
                return;
            }
        }
        plugin.beerService().purchase(player, brand, amount,
                plugin.pub().pubPrice(brand.style().price()), true);
    }

    // ------------------------------------------------------------------
    // /quiz
    // ------------------------------------------------------------------

    private void quiz(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "start" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                if (!sender.hasPermission("guab.pub.quiz.start")) {
                    error(sender, "You need guab.pub.quiz.start to start the quiz.");
                    return;
                }
                plugin.quiz().begin(sender);
            }
            case "stop" -> {
                if (!requireQuizAdmin(sender)) {
                    return;
                }
                plugin.quiz().stopBy(sender, senderName(sender));
            }
            case "skip" -> {
                if (!requireQuizAdmin(sender)) {
                    return;
                }
                plugin.quiz().skip(sender);
            }
            case "status" -> {
                if (!requireQuizAdmin(sender)) {
                    return;
                }
                plugin.quiz().status(sender);
            }
            default -> error(sender, "Use /quiz [start|stop|skip|status].");
        }
    }

    private boolean requireQuizAdmin(CommandSender sender) {
        if (!sender.hasPermission(QUIZ_ADMIN)) {
            error(sender, "You need " + QUIZ_ADMIN + " to manage the quiz.");
            return false;
        }
        return true;
    }

    private String senderName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "Console";
    }

    // ------------------------------------------------------------------
    // /pub
    // ------------------------------------------------------------------

    private void pub(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("happyhour")) {
            info(sender, "Use /pub happyhour <minutes|off>.");
            return;
        }
        if (!sender.hasPermission(QUIZ_ADMIN) && !sender.hasPermission(PUB_ADMIN)) {
            error(sender, "You need " + QUIZ_ADMIN + " or " + PUB_ADMIN
                    + " to run happy hour.");
            return;
        }
        PubManager pub = plugin.pub();
        if (args.length < 2) {
            if (pub.happyHourActive()) {
                long remaining = seconds(pub.happyHourRemainingMillis());
                info(sender, "Happy hour is on: " + (remaining / 60) + "m "
                        + (remaining % 60) + "s left. Half-price pints, double units, "
                        + "double quiz prizes.");
            } else {
                info(sender, "Happy hour is off. Start it with /pub happyhour <minutes>.");
            }
            return;
        }
        String value = args[1].toLowerCase(Locale.ROOT);
        if (value.equals("off") || value.equals("stop") || value.equals("end")) {
            boolean wasActive = pub.happyHourActive();
            pub.setHappyHour(0);
            if (wasActive) {
                Bukkit.broadcast(prefix().append(Component.text(
                        "Happy hour is over. Full prices and normal quiz prizes return.",
                        NamedTextColor.GRAY)));
            } else {
                info(sender, "Happy hour is already off.");
            }
            return;
        }
        int minutes;
        try {
            minutes = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            error(sender, "Minutes must be a number from 1 to 1440, or off.");
            return;
        }
        if (minutes < 1 || minutes > 24 * 60) {
            error(sender, "Minutes must be a number from 1 to 1440, or off.");
            return;
        }
        pub.setHappyHour(minutes);
        Bukkit.broadcast(prefix().append(Component.text(
                "Happy hour! Half-price pints, double units and double quiz prizes for the next "
                        + minutes + " minute" + (minutes == 1 ? "" : "s") + ".",
                NamedTextColor.GOLD)));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PointsService points() {
        var registration = Bukkit.getServicesManager().getRegistration(PointsService.class);
        return registration == null ? null : registration.getProvider();
    }

    private Component prefix() {
        return Component.text("[Spoons] ", NamedTextColor.GOLD);
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Spoons] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Spoons] ", NamedTextColor.RED)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    private static long seconds(long millis) {
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("pub")) {
            if (args.length == 1) {
                return matching(List.of("happyhour"), args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("happyhour")) {
                return matching(List.of("15", "30", "60", "off"), args[1]);
            }
            return List.of();
        }
        if (name.equals("pints") && args.length == 1) {
            return matching(List.of("top", "units"), args[0]);
        }
        if (name.equals("round") && args.length == 1) {
            List<String> options = new ArrayList<>(styleIds());
            options.add("confirm");
            options.add("cancel");
            return matching(options, args[0]);
        }
        if (name.equals("quiz") && args.length == 1) {
            return matching(List.of("start", "stop", "skip", "status"), args[0]);
        }
        if (!name.equals("beer")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("menu", "list", "stats", "again", "buy"));
            if (plugin.getConfig().getBoolean("beer.milk.enabled", true)) {
                options.add("milk");
            }
            return matching(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            return matching(styleIds(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("again")) {
            return matching(List.of("1", "2", "4", "8", "16"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("buy")) {
            return matching(List.of("1", "2", "4", "8", "16"), args[2]);
        }
        return List.of();
    }

    private List<String> styleIds() {
        List<String> ids = new ArrayList<>();
        for (BeerBrand brand : plugin.beer().allBrands()) {
            ids.add(brand.id());
        }
        for (BeerStyle style : plugin.beer().all()) {
            ids.add(style.id());
        }
        return ids;
    }

    private List<String> matching(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }

    private record PendingRound(String brandId, List<UUID> drinkers, int costEach, long expiresAt) {
    }
}
