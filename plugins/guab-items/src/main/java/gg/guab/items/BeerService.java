package gg.guab.items;

import gg.guab.core.PointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Shared pour/charge/give logic for the tap, the menu, /beer and /round.
 */
public final class BeerService {

    private final GuabItemsPlugin plugin;
    private final Map<UUID, LastOrder> lastOrders = new HashMap<>();

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
        if (total > Integer.MAX_VALUE) {
            error(player, "That order is too large for the till.");
            return false;
        }
        if (total > 0) {
            PointsService points = points();
            if (points == null) {
                error(player, "The till is broken; the points ledger is unavailable.");
                return false;
            }
            if (!points.take(player.getUniqueId(), (int) total)) {
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
