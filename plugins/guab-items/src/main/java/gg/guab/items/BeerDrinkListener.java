package gg.guab.items;

import gg.guab.core.HudChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Finished-drink handling. Vanilla drives the 1.1s CONSUMABLE animation and
 * fires {@link PlayerItemConsumeEvent} when the sip completes, so the pub reacts
 * to that instead of cancelling the right-click. Milk still sobers the drinker
 * up; beer records the pint, applies the tier and staggers its sounds.
 */
public final class BeerDrinkListener implements Listener {

    /** Ticks between the pour, the sip and the burp so one drink sounds deliberate. */
    private static final long SOUND_STAGGER_TICKS = 8L;

    private final GuabItemsPlugin plugin;
    private final Set<UUID> lethalDeaths = new HashSet<>();

    public BeerDrinkListener(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (plugin.items().isMilk(item)) {
            handleMilk(event);
            return;
        }
        if (plugin.items().typeOf(item) != ItemType.BEER) {
            return;
        }
        handleBeer(event);
    }

    private void handleMilk(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("guab.pub.use")) {
            event.setCancelled(true);
            return;
        }
        plugin.pub().soberUp(player);
        player.sendMessage(Component.text("The milk sobers you up.", NamedTextColor.WHITE));
    }

    private void handleBeer(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
            return;
        }
        if (!player.hasPermission("guab.pub.use")) {
            event.setCancelled(true);
            actionBar(player, Component.text("You cannot use Guab items.", NamedTextColor.RED));
            return;
        }

        long remaining = plugin.cooldowns().remainingMillis(player.getUniqueId(), ItemType.BEER);
        if (remaining > 0L) {
            event.setCancelled(true);
            actionBar(player, Component.text("Beer ready in "
                    + String.format(Locale.ROOT, "%.1f", remaining / 1000.0) + "s",
                    NamedTextColor.GRAY));
            return;
        }

        // Rebuild legacy or hand-edited pints before their brand is read, so the
        // consume event uses the same canonical item the rest of the plugin sees.
        ItemStack stack = event.getItem();
        if (plugin.items().needsCanonicalize(stack)) {
            stack = plugin.items().canonicalize(stack);
            event.setItem(stack);
        }
        drink(player, stack);
    }

    private void drink(Player player, ItemStack stack) {
        BeerBrand brand = plugin.items().beerBrandOf(stack);
        if (brand == null) {
            brand = plugin.beer().houseBrand();
        }
        BeerStyle style = brand.style();
        plugin.cooldowns().start(player.getUniqueId(), ItemType.BEER,
                plugin.config().beerCooldownMillis());

        if (style.lethal()) {
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

        for (ItemsConfig.BeerEffect effect : style.effects()) {
            player.addPotionEffect(new PotionEffect(effect.type(), effect.durationTicks(),
                    effect.amplifier(), false, true, true));
        }
        plugin.pub().recordDrink(player, brand);
        player.sendMessage(Component.text("You crack open a cold " + brand.displayName() + ".",
                NamedTextColor.YELLOW));
        player.sendMessage(Component.text(style.randomMessage(), NamedTextColor.GRAY));
        playDrinkSounds(player);
    }

    /**
     * Pour, sip, burp. Each sound is a few ticks after the last so a single pint
     * does not fire everything on the same tick. Each scheduled play re-checks
     * the player's sound preference through {@link GuabSounds}.
     */
    private void playDrinkSounds(Player player) {
        GuabSounds.play(player, GuabSounds.BEER_POUR, 1.0f, 1.0f);
        scheduleSound(player, SOUND_STAGGER_TICKS,
                () -> GuabSounds.play(player, GuabSounds.DRINK_CLINK, 0.9f, 1.0f));
        scheduleSound(player, SOUND_STAGGER_TICKS,
                () -> GuabSounds.play(player, Sound.ENTITY_GENERIC_DRINK,
                        SoundCategory.PLAYERS, 1.0f, 0.9f));
        scheduleSound(player, SOUND_STAGGER_TICKS * 2,
                () -> GuabSounds.play(player, GuabSounds.HICCUP, 0.6f, 1.05f));
        scheduleSound(player, SOUND_STAGGER_TICKS * 2,
                () -> GuabSounds.play(player, Sound.ENTITY_PLAYER_BURP,
                        SoundCategory.PLAYERS, 0.6f, 1.1f));
    }

    private void scheduleSound(Player player, long delayTicks, Runnable sound) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                sound.run();
            }
        }, delayTicks);
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

    private void actionBar(Player player, Component message) {
        HudChannel channel = plugin.hudChannel();
        if (channel != null) {
            channel.send(player, message, HudChannel.PRIORITY_POINTS, 30);
            return;
        }
        if (plugin.preferences() != null
                && !plugin.preferences().actionbar(player.getUniqueId())) {
            return;
        }
        player.sendActionBar(message);
    }
}
