package gg.guab.items;

import gg.guab.core.PointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Shared pour/charge/give logic for the tap, the menu, /beer and /round.
 */
public final class BeerService {

    /** The paid rickroll contract is deliberately rare: five minutes per buyer. */
    private static final long RICKROLL_COOLDOWN_MILLIS = 5L * 60L * 1000L;

    private final GuabItemsPlugin plugin;
    private final Map<UUID, LastOrder> lastOrders = new HashMap<>();
    private final Map<UUID, Long> rickrollReadyAt = new HashMap<>();

    public BeerService(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean purchase(Player player, BeerStyle style, int amount, int unitPrice, boolean requirePub) {
        return purchase(player, plugin.beer().randomBrand(style), amount, unitPrice, requirePub);
    }

    public boolean purchase(Player player, BeerBrand brand, int amount, int unitPrice, boolean requirePub) {
        if (brand == null) {
            error(player, "That beer is off the menu.");
            return false;
        }
        if (plugin.pub() != null) {
            unitPrice = plugin.pub().pubPrice(unitPrice);
        }
        if (!player.hasPermission("guab.pub.use")) {
            error(player, "You are not old enough to drink here.");
            return false;
        }
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            error(player, "A spectator cannot order a pint.");
            return false;
        }
        amount = Math.clamp(amount, 1, 64);
        if (requirePub && !inPub(player) && !player.hasPermission("guab.pub.menu.remote")) {
            error(player, "Order at the bar. The pub is over there.");
            return false;
        }

        long total = (long) Math.max(0, unitPrice) * amount;
        if (total > 0) {
            PointsService points = points();
            if (points == null) {
                error(player, "The till is broken; the points ledger is unavailable.");
                return false;
            }
            if (!points.take(player.getUniqueId(), total)) {
                error(player, "That comes to " + total
                        + (total == 1 ? " point" : " points") + " and you cannot cover it.");
                return false;
            }
        }

        give(player, brand, amount);
        lastOrders.put(player.getUniqueId(), new LastOrder(brand.id(), amount));
        GuabSounds.play(player, Sound.BLOCK_BARREL_OPEN, SoundCategory.PLAYERS, 1.0f, 1.0f);
        String priceText = total > 0
                ? " for " + total + (total == 1 ? " point." : " points.")
                : " for free.";
        player.sendMessage(prefix().append(Component.text(
                "Poured " + amount + "x " + brand.displayName() + priceText,
                NamedTextColor.GOLD)));
        return true;
    }

    /** Replays the last successful /beer order, optionally with a new amount. */
    public boolean purchaseAgain(Player player, Integer amountOverride) {
        LastOrder last = lastOrders.get(player.getUniqueId());
        if (last == null) {
            error(player, "You have not ordered yet. Try /beer list.");
            return false;
        }
        BeerBrand brand = plugin.beer().findBrand(last.brandId());
        if (brand == null) {
            brand = plugin.beer().brandFor(last.brandId());
        }
        if (brand == null) {
            error(player, "That beer is off the menu now. Try /beer list.");
            return false;
        }
        int amount = amountOverride == null ? last.amount() : Math.clamp(amountOverride, 1, 64);
        return purchase(player, brand, amount, brand.style().price(), true);
    }

    public boolean buyMilk(Player player, boolean requirePub) {
        if (!plugin.getConfig().getBoolean("beer.milk.enabled", true)) {
            error(player, "The milk is off tonight.");
            return false;
        }
        if (!player.hasPermission("guab.pub.use")) {
            error(player, "You are not allowed to buy milk.");
            return false;
        }
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            error(player, "A spectator cannot buy milk.");
            return false;
        }
        if (plugin.pub() != null && plugin.pub().drunkUnits(player.getUniqueId()) <= 0.05) {
            error(player, "You're already sober. Save your points.");
            return false;
        }
        if (requirePub && !inPub(player) && !player.hasPermission("guab.pub.menu.remote")) {
            error(player, "Order at the bar. The pub is over there.");
            return false;
        }
        int cost = Math.max(0, plugin.getConfig().getInt("beer.milk.cost", 5));
        if (cost > 0) {
            PointsService points = points();
            if (points == null || !points.take(player.getUniqueId(), cost)) {
                error(player, "Milk is " + cost + " points and you cannot cover it.");
                return false;
            }
        }
        player.getInventory().addItem(plugin.items().createMilk()).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        GuabSounds.play(player, Sound.ENTITY_COW_MILK, SoundCategory.PLAYERS, 1.0f, 1.0f);
        player.sendMessage(prefix().append(Component.text(
                "Poured a pint of milk" + (cost > 0 ? " for " + cost + " points." : " for free.")
                        + " Drink it to sober up.", NamedTextColor.WHITE)));
        return true;
    }

    public void give(Player player, BeerStyle style, int amount) {
        give(player, plugin.beer().randomBrand(style), amount);
    }

    public void give(Player player, BeerBrand brand, int amount) {
        int remaining = Math.clamp(amount, 1, 64);
        int stackSize = Math.max(1, ItemType.BEER.material().getMaxStackSize());
        while (remaining > 0) {
            int batch = Math.min(remaining, stackSize);
            ItemStack beer = plugin.items().createBeer(brand, batch);
            player.getInventory().addItem(beer).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            remaining -= batch;
        }
    }

    public boolean inPub(Player player) {
        Location centre = plugin.pub().centre();
        if (centre == null || !player.getWorld().equals(centre.getWorld())) {
            return false;
        }
        double radius = Math.max(2.0, plugin.getConfig().getDouble("pub.round.radius", 14.0));
        return player.getLocation().distanceSquared(centre) <= radius * radius;
    }

    /**
     * A golden round: the buyer pays once and every eligible player online gets a
     * random house pint plus a small firework. Returns false before charging when
     * the buyer cannot cover the price or the till is unavailable.
     */
    public boolean buyGoldenRound(Player buyer, int cost) {
        if (!buyer.hasPermission("guab.pub.use")) {
            error(buyer, "You are not old enough to call a round.");
            return false;
        }
        if (buyer.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            error(buyer, "A spectator cannot call a round.");
            return false;
        }
        int price = Math.max(0, cost);
        if (price > 0) {
            PointsService points = points();
            if (points == null) {
                error(buyer, "The till is broken; the points ledger is unavailable.");
                return false;
            }
            if (!points.take(buyer.getUniqueId(), price)) {
                error(buyer, "A golden round costs " + price
                        + " points and you cannot cover it.");
                return false;
            }
        }

        List<Player> recipients = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getGameMode() == org.bukkit.GameMode.SPECTATOR
                    || !online.hasPermission("guab.pub.use")) {
                continue;
            }
            recipients.add(online);
        }
        for (Player recipient : recipients) {
            give(recipient, plugin.beer().randomHouseBrand(), 1);
            goldenConfetti(recipient);
        }

        Bukkit.broadcast(prefix().append(Component.text(
                buyer.getName() + " calls a Golden Round — " + recipients.size()
                        + (recipients.size() == 1 ? " pint" : " pints") + " on the house!",
                NamedTextColor.GOLD)));
        return true;
    }

    private void goldenConfetti(Player player) {
        Location location = player.getLocation().add(0, 1.0, 0);
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Firework firework = world.spawn(location, Firework.class, entity -> {
            FireworkMeta meta = entity.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(FireworkEffect.Type.BALL)
                    .withColor(Color.YELLOW, Color.ORANGE)
                    .withFade(Color.WHITE)
                    .trail(true)
                    .flicker(true)
                    .build());
            meta.setPower(0);
            entity.setFireworkMeta(meta);
        });
        firework.detonate();
        world.spawnParticle(Particle.FIREWORK, location, 16, 0.4, 0.5, 0.4, 0.05);
    }

    /**
     * A paid rickroll contract. Charges the buyer, then dispatches the existing
     * rickroll command for an online target from the console. The rickroll plugin
     * still owns opt-outs, cooldowns and queueing. Any dispatch failure refunds.
     */
    public boolean hireRickroll(Player buyer, String targetName, int cost) {
        if (!buyer.hasPermission("guab.pub.use")) {
            error(buyer, "You are not old enough to hire a rickroll.");
            return false;
        }
        if (buyer.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            error(buyer, "A spectator cannot hire a rickroll.");
            return false;
        }
        String name = targetName == null ? "" : targetName.trim();
        if (name.isEmpty()) {
            error(buyer, "Type the name of an online player first.");
            return false;
        }
        Player target = Bukkit.getPlayerExact(name);
        if (target == null || !target.isOnline()) {
            error(buyer, "No online player called " + name + ".");
            return false;
        }
        if (target.getUniqueId().equals(buyer.getUniqueId())) {
            error(buyer, "You cannot rickroll yourself. That is just a mirror.");
            return false;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("GuabRickroll")) {
            error(buyer, "The cinema is closed; the rickroll plugin is not running.");
            return false;
        }

        long now = System.currentTimeMillis();
        long readyAt = rickrollReadyAt.getOrDefault(buyer.getUniqueId(), 0L);
        if (now < readyAt) {
            long seconds = (readyAt - now + 999L) / 1000L;
            error(buyer, "The contract desk is busy for another " + seconds + "s.");
            return false;
        }

        int price = Math.max(0, cost);
        PointsService points = points();
        if (price > 0) {
            if (points == null) {
                error(buyer, "The till is broken; the points ledger is unavailable.");
                return false;
            }
            if (!points.take(buyer.getUniqueId(), price)) {
                error(buyer, "That contract comes to " + price
                        + " points and you cannot cover it.");
                return false;
            }
        }

        boolean dispatched;
        try {
            // Console passes the rickroll plugin's admin gate, and the plugin
            // still enforces opt-out, cooldown and queue rules for the target.
            dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "rickroll test " + target.getName());
            if (!dispatched) {
                // Older builds exposed the target directly rather than under test.
                dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "rickroll " + target.getName());
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Paid rickroll failed: " + exception.getMessage());
            dispatched = false;
        }
        if (!dispatched) {
            if (price > 0 && points != null) {
                points.add(buyer.getUniqueId(), price);
            }
            error(buyer, "The rickroll desk is closed; your points were refunded.");
            return false;
        }

        rickrollReadyAt.put(buyer.getUniqueId(), now + RICKROLL_COOLDOWN_MILLIS);
        buyer.sendMessage(prefix().append(Component.text(
                "Contract accepted: " + target.getName()
                        + " is getting the full cinema treatment.",
                NamedTextColor.LIGHT_PURPLE)));
        return true;
    }

    public static String priceLabel(int amount, int unitPrice) {
        return amount + "x " + String.format(Locale.ROOT, "%d", unitPrice * amount);
    }

    private PointsService points() {
        var registration = Bukkit.getServicesManager().getRegistration(PointsService.class);
        return registration == null ? null : registration.getProvider();
    }

    private Component prefix() {
        return Component.text("[Spoons] ", NamedTextColor.GOLD);
    }

    private void error(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    private record LastOrder(String brandId, int amount) {
    }
}
