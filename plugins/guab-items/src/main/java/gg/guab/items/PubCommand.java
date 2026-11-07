package gg.guab.items;

import gg.guab.core.PointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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
            case "round" -> round(sender, args);
            case "pints" -> pints(sender, args);
            case "quiz" -> quiz(sender);
            case "beer" -> beer(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void round(CommandSender sender, String[] args) {
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

        BeerBrand brand = args.length > 0 ? plugin.beer().brandFor(args[0]) : plugin.beer().houseBrand();
        if (brand == null) {
            error(sender, "Unknown beer: " + args[0] + ". Try /beer list.");
            return;
        }
        BeerStyle style = brand.style();
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

        int costEach = args.length > 0
                ? Math.max(0, style.price())
                : Math.max(0, plugin.getConfig().getInt("pub.round.cost-per-pint", 1));
        long total = (long) costEach * drinkers.size();
        PointsService points = points();
        if (total > 0) {
            if (points == null || total > Integer.MAX_VALUE
                    || !points.take(player.getUniqueId(), (int) total)) {
                error(sender, "A round costs " + total + " points and you cannot cover it.");
                return;
            }
        }
        roundCooldowns.put(player.getUniqueId(), now);

        for (Player drinker : drinkers) {
            plugin.beerService().give(drinker, brand, 1);
            drinker.playSound(drinker.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,
                    SoundCategory.PLAYERS, 0.8f, 1.1f);
            drinker.sendMessage(Component.text("[Spoons] ", NamedTextColor.GOLD)
                    .append(Component.text(player.getName() + " bought the round — "
                            + brand.displayName() + " for everyone. Get it down you.",
                            NamedTextColor.YELLOW)));
        }
    }

    private void pints(CommandSender sender, String[] args) {
        PubManager pub = plugin.pub();
        if (sender instanceof Player player) {
            double units = pub.drunkUnits(player.getUniqueId());
            info(sender, "You have drunk " + pub.pints(player.getUniqueId()) + " pints ("
                    + String.format(Locale.ROOT, "%.1f", units) + " units right now, "
                    + pub.tierLabel(units) + ").");
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
            case "list" -> {
                info(sender, "The Wetherspoons menu:");
                for (BeerBrand brand : plugin.beer().allBrands()) {
                    BeerStyle style = brand.style();
                    sender.sendMessage(Component.text("  " + brand.displayName(), style.colour())
                            .append(Component.text("  ·  " + style.displayName() + "  ·  "
                                            + String.format(Locale.ROOT, "%.1f", brand.abv()) + "%  ·  "
                                            + brand.unitsLabel() + " units  ·  " + style.price() + " pt",
                                    NamedTextColor.GRAY))
                            .append(style.lethal()
                                    ? Component.text("  ☠", NamedTextColor.RED) : Component.empty()));
                }
                int milkCost = Math.max(0, plugin.getConfig().getInt("beer.milk.cost", 5));
                sender.sendMessage(Component.text("  Milk", NamedTextColor.WHITE)
                        .append(Component.text("  ·  sobers you up  ·  " + milkCost + " pt",
                                NamedTextColor.GRAY)));
            }
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
            case "buy", "pour" -> buy(sender, args);
            default -> {
                error(sender, "Use /beer [menu|list|stats|milk|buy <beer> [amount]].");
            }
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
        plugin.beerService().purchase(player, brand, amount, brand.style().price(), true);
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
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("pints") && args.length == 1) {
            return matching(List.of("top", "units"), args[0]);
        }
        if (name.equals("round") && args.length == 1) {
            return matching(styleIds(), args[0]);
        }
        if (!name.equals("beer")) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(List.of("menu", "list", "stats", "milk", "buy"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            return matching(styleIds(), args[1]);
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
}
