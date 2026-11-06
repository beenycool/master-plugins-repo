package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Right-click behaviour and the guards that keep renamed/crafted lookalikes inert.
 */
public final class ItemListener implements Listener {

    private final GuabItemsPlugin plugin;

    public ItemListener(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) {
            return;
        }

        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();

        // When both hands are full, the main hand wins. Cancelling and ignoring the
        // off-hand event here is what stops one physical right-click from firing twice.
        if (hand == EquipmentSlot.OFF_HAND && plugin.items().isTagged(inventory.getItemInMainHand())) {
            event.setCancelled(true);
            return;
        }

        ItemStack stack = hand == EquipmentSlot.HAND
                ? inventory.getItemInMainHand()
                : inventory.getItemInOffHand();
        ItemType type = plugin.items().typeOf(stack);
        if (type == null) {
            return;
        }

        // This is one of our items, so deny the vanilla use (including wind charge
        // explosions and block interaction) for both hands before doing anything else.
        event.setCancelled(true);

        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (!player.hasPermission("guab.items.use")) {
            actionBar(player, Component.text("You cannot use Guab items.", NamedTextColor.RED));
            return;
        }

        long remaining = plugin.cooldowns().remainingMillis(player.getUniqueId(), type);
        if (remaining > 0L) {
            actionBar(player, Component.text(type.plainName() + " ready in "
                    + String.format(Locale.ROOT, "%.1f", remaining / 1000.0) + "s", NamedTextColor.GRAY));
            return;
        }

        switch (type) {
            case WIND_CHARGE -> useWindCharge(player);
            case JUMP_BOOST -> useJumpBoost(player);
            case BEER -> useBeer(player);
            case SPOON -> useSpoon(player);
        }

        long cooldown = switch (type) {
            case WIND_CHARGE -> plugin.config().windChargeCooldownMillis();
            case JUMP_BOOST -> plugin.config().jumpBoostCooldownMillis();
            case BEER -> plugin.config().beerCooldownMillis();
            case SPOON -> plugin.config().spoonCooldownMillis();
        };
        plugin.cooldowns().start(player.getUniqueId(), type, cooldown);

        boolean consume = switch (type) {
            case WIND_CHARGE -> plugin.config().windChargeConsume();
            case JUMP_BOOST, BEER -> true;
            case SPOON -> false;
        };
        if (consume) {
            consumeOne(player, hand);
        }
    }

    private void useWindCharge(Player player) {
        ItemsConfig config = plugin.config();

        Vector look = player.getEyeLocation().getDirection().normalize();
        Vector launch = look.multiply(config.windChargePower());
        launch.setY(Math.max(launch.getY(), 0.0) + config.windChargeVerticalBoost());
        player.setVelocity(launch);
        player.setFallDistance(0.0f);

        double radiusSquared = config.windChargeRadius() * config.windChargeRadius();
        for (Player nearby : player.getWorld().getPlayers()) {
            if (nearby.equals(player) || nearby.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (player.getLocation().distanceSquared(nearby.getLocation()) > radiusSquared) {
                continue;
            }
            Vector push = nearby.getLocation().toVector().subtract(player.getLocation().toVector());
            push.setY(0.0);
            if (push.lengthSquared() < 0.0001) {
                continue;
            }
            push.normalize().multiply(config.windChargeKnockback());
            push.setY(config.windChargeKnockbackVertical());
            nearby.setVelocity(nearby.getVelocity().add(push));
            nearby.setFallDistance(0.0f);
        }

        actionBar(player, Component.text("Wind Charge!", NamedTextColor.AQUA));
    }

    private void useJumpBoost(Player player) {
        int configured = plugin.config().jumpBoostAmplifier();
        PotionEffect current = player.getPotionEffect(PotionEffectType.JUMP_BOOST);

        // Never downgrade a stronger Jump Boost the player already has; just make
        // the current level permanent. The feather is still consumed.
        int amplifier = current == null ? configured : Math.max(configured, current.getAmplifier());
        PotionEffect effect = new PotionEffect(
                PotionEffectType.JUMP_BOOST,
                PotionEffect.INFINITE_DURATION,
                amplifier,
                false,
                true,
                true);
        player.addPotionEffect(effect);
        actionBar(player, Component.text(
                current == null ? "Permanent Jump Boost!" : "Jump Boost refreshed!",
                NamedTextColor.GREEN));
    }

    private void useBeer(Player player) {
        for (ItemsConfig.BeerEffect effect : plugin.config().beerEffects()) {
            player.addPotionEffect(new PotionEffect(
                    effect.type(), effect.durationTicks(), effect.amplifier(), false, true, true));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1.0f, 0.9f);

        List<String> lines = plugin.getConfig().getStringList("beer.messages");
        String line = lines.isEmpty()
                ? "You feel mildly Wetherspoons."
                : lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        player.sendMessage(Component.text(line, NamedTextColor.YELLOW));
    }

    private void useSpoon(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.4f);
        player.sendMessage(Component.text("Clang! You are now a Wetherspoon.", NamedTextColor.GRAY));
    }

    private void consumeOne(Player player, EquipmentSlot hand) {
        PlayerInventory inventory = player.getInventory();
        ItemStack stack = inventory.getItem(hand);
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        int amount = stack.getAmount();
        if (amount <= 1) {
            inventory.setItem(hand, null);
        } else {
            stack.setAmount(amount - 1);
            inventory.setItem(hand, stack);
        }
    }

    private void actionBar(Player player, Component message) {
        player.sendActionBar(message);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.cooldowns().clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }

        if (plugin.items().isTagged(result)) {
            ItemStack first = event.getInventory().getItem(0);
            ItemType firstType = plugin.items().typeOf(first);
            ItemType resultType = plugin.items().typeOf(result);
            if (firstType == null || resultType != firstType) {
                // A normal item must never gain our tag from the second anvil slot.
                ItemStack cleaned = result.clone();
                plugin.items().stripTag(cleaned);
                plugin.items().stripFakeName(cleaned);
                event.setResult(cleaned);
                return;
            }
            if (result.getAmount() > first.getAmount()) {
                // Never let an anvil turn one genuine item into more than the left
                // input held.
                ItemStack capped = result.clone();
                capped.setAmount(first.getAmount());
                event.setResult(plugin.items().canonicalize(capped));
                return;
            }
            // Genuine item: reset any anvil rename back to the canonical name/lore.
            event.setResult(plugin.items().canonicalize(result));
            return;
        }

        if (plugin.items().hasFakeName(result)) {
            ItemStack cleaned = result.clone();
            plugin.items().stripFakeName(cleaned);
            event.setResult(cleaned);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }

        if (plugin.items().isTagged(result)) {
            // No Guab item has a recipe. A tagged result means a recipe copied or
            // forged the tag, so deny the craft outright.
            event.getInventory().setResult(null);
            return;
        }

        if (plugin.items().hasFakeName(result)) {
            ItemStack cleaned = result.clone();
            plugin.items().stripFakeName(cleaned);
            event.getInventory().setResult(cleaned);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(CraftItemEvent event) {
        if (plugin.items().isTagged(event.getCurrentItem())) {
            event.setCancelled(true);
        }
    }
}
