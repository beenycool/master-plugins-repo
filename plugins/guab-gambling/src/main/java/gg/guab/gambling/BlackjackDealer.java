package gg.guab.gambling;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The physical blackjack dealer in the pub. Right-click opens a 26.3 dialog
 * where the player types a bet; an existing hand is re-printed with its action
 * buttons instead of starting a second hand.
 */
public final class BlackjackDealer implements Listener {

    private final GuabGamblingPlugin plugin;
    private final Blackjack blackjack;
    private final NamespacedKey dealerKey;
    private final NamespacedKey hologramKey;
    private Villager dealer;
    private TextDisplay hologram;

    public BlackjackDealer(GuabGamblingPlugin plugin, Blackjack blackjack) {
        this.plugin = plugin;
        this.blackjack = blackjack;
        this.dealerKey = new NamespacedKey(plugin, "blackjack_dealer");
        this.hologramKey = new NamespacedKey(plugin, "blackjack_dealer_holo");
    }

    public void start() {
        spawn();
    }

    public void stop() {
        if (dealer != null && dealer.isValid()) {
            dealer.remove();
        }
        dealer = null;
        if (hologram != null && hologram.isValid()) {
            hologram.remove();
        }
        hologram = null;
    }

    public void reload() {
        stop();
        spawn();
    }

    public void moveTo(Player player) {
        Location location = player.getLocation();
        plugin.getConfig().set("blackjack.dealer.world", location.getWorld().getName());
        plugin.getConfig().set("blackjack.dealer.x", location.getX());
        plugin.getConfig().set("blackjack.dealer.y", location.getY());
        plugin.getConfig().set("blackjack.dealer.z", location.getZ());
        plugin.getConfig().set("blackjack.dealer.yaw", location.getYaw());
        plugin.saveConfig();
        reload();
        player.sendMessage(Component.text("[Blackjack] ", NamedTextColor.GOLD)
                .append(Component.text("Dealer moved here.", NamedTextColor.GRAY)));
    }

    private void spawn() {
        if (!plugin.getConfig().getBoolean("blackjack.dealer.enabled", true)) {
            return;
        }
        Location location = location();
        if (location == null || location.getWorld() == null) {
            return;
        }
        World world = location.getWorld();
        world.getChunkAt(location).load();
        removeOld(world, location);

        dealer = world.spawn(location, Villager.class, villager -> {
            villager.setAI(false);
            villager.setSilent(true);
            villager.setInvulnerable(true);
            villager.setPersistent(true);
            villager.setRemoveWhenFarAway(false);
            villager.setCollidable(false);
            villager.setProfession(Villager.Profession.CLERIC);
            villager.setVillagerType(Villager.Type.PLAINS);
            villager.customName(Component.text("Blackjack Dealer", NamedTextColor.GOLD));
            villager.setCustomNameVisible(true);
            villager.getPersistentDataContainer().set(dealerKey, PersistentDataType.BYTE, (byte) 1);
        });

        Location holoLocation = location.clone().add(0, 2.25, 0);
        hologram = world.spawn(holoLocation, TextDisplay.class, display -> {
            display.text(Component.text("BLACKJACK", NamedTextColor.GOLD)
                    .append(Component.newline())
                    .append(Component.text("Right-click to play", NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Dealer stands on 17", NamedTextColor.GRAY)));
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(false);
            display.setShadowed(true);
            display.setPersistent(false);
            display.setViewRange(0.9f);
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            display.getPersistentDataContainer().set(hologramKey, PersistentDataType.BYTE, (byte) 1);
        });
        hologram.setPersistent(true);
    }

    private void removeOld(World world, Location location) {
        for (Entity entity : world.getNearbyEntities(location, 6, 6, 6)) {
            if (entity.getPersistentDataContainer().has(dealerKey, PersistentDataType.BYTE)
                    || entity.getPersistentDataContainer().has(hologramKey, PersistentDataType.BYTE)) {
                entity.remove();
            }
        }
    }

    private Location location() {
        String worldName = plugin.getConfig().getString("blackjack.dealer.world", "guab_casino");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world,
                plugin.getConfig().getDouble("blackjack.dealer.x", -18.0),
                plugin.getConfig().getDouble("blackjack.dealer.y", 65.0),
                plugin.getConfig().getDouble("blackjack.dealer.z", -14.0),
                (float) plugin.getConfig().getDouble("blackjack.dealer.yaw", 0.0),
                0.0f);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager clicked)
                || !clicked.getPersistentDataContainer().has(dealerKey, PersistentDataType.BYTE)) {
            return;
        }
        event.setCancelled(true);
        open(event.getPlayer());
    }

    /** Open the dealer's bet dialog, or reprint the current hand. */
    public void open(Player player) {
        if (blackjack.hasHand(player)) {
            blackjack.showHand(player);
            return;
        }
        openDialog(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Villager villager
                && villager.getPersistentDataContainer().has(dealerKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    private void openDialog(Player player) {
        int min = Math.max(1, plugin.getConfig().getInt("blackjack.min-bet", 10));
        int max = plugin.getConfig().getInt("blackjack.max-bet", -1);
        String range = max > 0 ? min + "–" + max : min + "+";

        ActionButton deal = ActionButton.builder(Component.text("Deal", NamedTextColor.GOLD))
                .width(100)
                .action(DialogAction.customClick(
                        (DialogResponseView response, Audience audience) -> {
                            if (!(audience instanceof Player clicker)) {
                                return;
                            }
                            String raw = response.getText("bet");
                            int bet = min;
                            if (raw != null && !raw.isBlank()) {
                                try {
                                    bet = Integer.parseInt(raw.trim());
                                } catch (NumberFormatException ignored) {
                                    // The normal start() validation will explain the range.
                                }
                            }
                            int finalBet = bet;
                            plugin.getServer().getScheduler().runTask(plugin,
                                    () -> blackjack.start(clicker, finalBet));
                        },
                        options()))
                .build();

        ActionButton close = ActionButton.builder(Component.text("Not tonight", NamedTextColor.GRAY))
                .width(100)
                .action(DialogAction.customClick(
                        (DialogResponseView response, Audience audience) -> audience.closeDialog(),
                        options()))
                .build();

        DialogBase base = DialogBase.builder(Component.text("The Blackjack Dealer", NamedTextColor.GOLD))
                .externalTitle(Component.text("Blackjack", NamedTextColor.GOLD))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(List.of(
                        DialogBody.plainMessage(Component.text(
                                "Good evening. Blackjack pays 3:2, the dealer stands on 17, "
                                        + "and every chip is a Guab point.", NamedTextColor.GRAY), 280),
                        DialogBody.plainMessage(Component.text(
                                "Bets: " + range + " points. Type your bet below, then press Deal.",
                                NamedTextColor.WHITE), 280)))
                .inputs(List.of(DialogInput.text("bet", Component.text("Bet amount"))
                        .initial(String.valueOf(min))
                        .maxLength(9)
                        .build()))
                .build();

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.multiAction(List.of(deal), close, 1)));
        player.showDialog(dialog);
    }

    private ClickCallback.Options options() {
        return ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofMinutes(10))
                .build();
    }

    public String describe() {
        Location location = location();
        if (location == null) {
            return "unplaced";
        }
        return location.getWorld().getName() + " " + String.format(Locale.ROOT, "%.1f %.1f %.1f",
                location.getX(), location.getY(), location.getZ());
    }
}
