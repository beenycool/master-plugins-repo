package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Finished-drink handling. The 26.3 CONSUMABLE component plays the animation,
 * sound and style effects; this listener records the pint and prints the
 * brand-specific flavour line.
 */
public final class BeerDrinkListener implements Listener {

    private final GuabItemsPlugin plugin;
    private final Set<UUID> lethalDeaths = new HashSet<>();

    public BeerDrinkListener(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        if (plugin.items().isMilk(item)) {
            if (!player.hasPermission("guab.items.use")) {
                event.setCancelled(true);
                return;
            }
            plugin.pub().soberUp(player);
            player.sendMessage(Component.text("The milk sobers you up.", NamedTextColor.WHITE));
            return;
        }
        if (plugin.items().typeOf(item) != ItemType.BEER) {
            return;
        }
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

        if (brand.style().lethal()) {
            player.sendMessage(Component.text("You drink the " + brand.displayName()
                    + ". It is instantly, catastrophically fatal.", NamedTextColor.RED));
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && !player.isDead()) {
                    plugin.pub().recordLethalDrink(player);
                    lethalDeaths.add(player.getUniqueId());
                    player.setHealth(0.0);
                }
            });
            return;
        }

        plugin.pub().recordDrink(player, brand);
        GuabSounds.play(player, GuabSounds.BEER_POUR, 1.0f, 1.0f);

        player.sendMessage(Component.text("You crack open a cold " + brand.displayName() + ".",
                NamedTextColor.YELLOW));
        player.sendMessage(Component.text(brand.style().randomMessage(), NamedTextColor.GRAY));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (lethalDeaths.remove(event.getEntity().getUniqueId())) {
            event.deathMessage(Component.text(event.getEntity().getName()
                    + " drank a Baileys and Lime. It was fatal.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lethalDeaths.remove(event.getPlayer().getUniqueId());
    }
}
