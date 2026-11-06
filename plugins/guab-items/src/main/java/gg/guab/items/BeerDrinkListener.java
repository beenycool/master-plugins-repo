package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * Finished-drink handling. The 26.3 CONSUMABLE component plays the animation,
 * sound and style effects; this listener records the pint and prints the
 * brand-specific flavour line.
 */
public final class BeerDrinkListener implements Listener {

    private final GuabItemsPlugin plugin;

    public BeerDrinkListener(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (plugin.items().typeOf(item) != ItemType.BEER) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
            return;
        }
        if (!player.hasPermission("guab.items.use")) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("You are not old enough to drink here.", NamedTextColor.RED));
            return;
        }

        long remaining = plugin.cooldowns().remainingMillis(player.getUniqueId(), ItemType.BEER);
        if (remaining > 0L) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("The next sip is ready in "
                    + String.format(Locale.ROOT, "%.1f", remaining / 1000.0) + "s",
                    NamedTextColor.GRAY));
            return;
        }

        BeerBrand brand = plugin.items().beerBrandOf(item);
        if (brand == null) {
            brand = plugin.beer().houseBrand();
        }
        plugin.cooldowns().start(player.getUniqueId(), ItemType.BEER,
                plugin.config().beerCooldownMillis());
        plugin.pub().recordDrink(player, brand.style());

        player.sendMessage(Component.text("You crack open a cold " + brand.displayName() + ".",
                NamedTextColor.YELLOW));
        player.sendMessage(Component.text(brand.style().randomMessage(), NamedTextColor.GRAY));
    }
}
