package gg.guab.items;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The 26.3 dialog beer menu. The shelves and the tap both open this; each style
 * is a real button that charges the ledger and pours the matching pint.
 */
public final class BeerMenuDialog {

    private final GuabItemsPlugin plugin;

    public BeerMenuDialog(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!plugin.getConfig().getBoolean("beer.menu.enabled", true)) {
            player.sendMessage(Component.text("[Spoons] ", NamedTextColor.GOLD)
                    .append(Component.text("The menu is chalked off tonight.", NamedTextColor.RED)));
            return;
        }

        int balance = plugin.pub().balance(player.getUniqueId());
        double units = plugin.pub().drunkUnits(player.getUniqueId());
        String tier = plugin.pub().tierLabel(units);

        List<DialogBody> body = new ArrayList<>();
        int houseCost = Math.max(0, plugin.getConfig().getInt("beer.cost", 1));
        body.add(DialogBody.plainMessage(Component.text(
                "Cheap pints, real beer names and absolutely no table service. "
                        + "Pick exactly what you want below.", NamedTextColor.GRAY), 300));
        body.add(DialogBody.plainMessage(Component.text(
                "Balance " + balance + " points   ·   " + String.format(Locale.ROOT, "%.1f", units)
                        + " units   ·   " + tier,
                NamedTextColor.GOLD), 300));
        body.add(DialogBody.plainMessage(Component.text(
                "Pick a pint below. The buttons buy it directly; "
                        + "the house pint is a random cheap tap pint.",
                NamedTextColor.WHITE), 300));

        List<ActionButton> actions = new ArrayList<>();
        actions.add(ActionButton.builder(Component.text("House pint · " + houseCost + " pt",
                        NamedTextColor.YELLOW))
                .tooltip(Component.text("A random cheap pint from the tap."))
                .width(180)
                .action(DialogAction.customClick(
                        (DialogResponseView response, Audience audience) -> {
                            if (audience instanceof Player clicker) {
                                plugin.getServer().getScheduler().runTask(plugin,
                                        () -> plugin.beerService().purchase(clicker,
                                                plugin.beer().randomHouseBrand(), 1, houseCost, true));
                            }
                        },
                        options()))
                .build());
        int milkCost = Math.max(0, plugin.getConfig().getInt("beer.milk.cost", 5));
        actions.add(ActionButton.builder(Component.text("Milk · " + milkCost + " pt",
                        NamedTextColor.WHITE))
                .tooltip(Component.text("Sober up: clears the drunk meter and potion effects."))
                .width(180)
                .action(DialogAction.customClick(
                        (DialogResponseView response, Audience audience) -> {
                            if (audience instanceof Player clicker) {
                                plugin.getServer().getScheduler().runTask(plugin,
                                        () -> plugin.beerService().buyMilk(clicker, true));
                            }
                        },
                        options()))
                .build());
        for (BeerBrand brand : plugin.beer().allBrands()) {
            BeerStyle style = brand.style();
            actions.add(ActionButton.builder(Component.text(
                            "Pour " + brand.displayName() + " · " + style.price() + " pt", style.colour()))
                    .tooltip(Component.text(style.displayName() + ", " + style.unitsLabel() + " units, "
                                    + String.format(Locale.ROOT, "%.1f", style.abv()) + "% ABV"
                                    + (style.lethal() ? " · ☠ Kills you." : "")))
                    .width(180)
                    .action(DialogAction.customClick(
                            (DialogResponseView response, Audience audience) -> {
                                if (audience instanceof Player clicker) {
                                    plugin.getServer().getScheduler().runTask(plugin,
                                            () -> plugin.beerService().purchase(clicker, brand, 1,
                                                    style.price(), true));
                                }
                            },
                            options()))
                    .build());
        }
        actions.add(ActionButton.builder(Component.text("My drinking record", NamedTextColor.AQUA))
                .width(180)
                .action(DialogAction.customClick(
                        (DialogResponseView response, Audience audience) -> {
                            if (audience instanceof Player clicker) {
                                plugin.getServer().getScheduler().runTask(plugin,
                                        () -> openStats(clicker));
                            }
                        },
                        options()))
                .build());

        player.showDialog(build("The Wetherspoons Bar", body, actions,
                Component.text("Back to the carpet", NamedTextColor.GRAY), 2));
    }

    public void openStats(Player player) {
        int pints = plugin.pub().pints(player.getUniqueId());
        double units = plugin.pub().drunkUnits(player.getUniqueId());
        String tier = plugin.pub().tierLabel(units);
        int rank = plugin.pub().rank(player.getUniqueId());
        String rankText = rank == 0 ? "unranked" : "rank " + rank + " of " + plugin.pub().pintsCount();
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text(
                        "Lifetime pints: " + pints + "   ·   " + rankText,
                        NamedTextColor.WHITE), 300),
                DialogBody.plainMessage(Component.text(
                        "Right now: " + String.format(Locale.ROOT, "%.1f", units)
                                + " units   ·   " + tier,
                        NamedTextColor.GOLD), 300),
                DialogBody.plainMessage(Component.text(
                        "The drunk meter burns off over time. Darts get harder before they get easier.",
                        NamedTextColor.GRAY), 300));

        player.showDialog(build("Your Spoons record", body,
                List.of(ActionButton.builder(Component.text("Back to the bar", NamedTextColor.YELLOW))
                        .width(160)
                        .action(DialogAction.customClick(
                                (DialogResponseView response, Audience audience) -> {
                                    if (audience instanceof Player clicker) {
                                        plugin.getServer().getScheduler().runTask(plugin,
                                                () -> open(clicker));
                                    }
                                },
                                options()))
                        .build()),
                Component.text("Close", NamedTextColor.GRAY), 1));
    }

    private Dialog build(String title, List<DialogBody> body, List<ActionButton> actions,
                         Component exitLabel, int columns) {
        Component titleComponent = Component.text(title, NamedTextColor.GOLD);
        ActionButton exit = ActionButton.builder(exitLabel)
                .width(140)
                .action(DialogAction.customClick(
                        (DialogResponseView response, Audience audience) -> audience.closeDialog(),
                        options()))
                .build();

        DialogBase base = DialogBase.builder(titleComponent)
                .externalTitle(titleComponent)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .inputs(List.of())
                .build();

        return Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.multiAction(actions, exit, columns)));
    }

    private ClickCallback.Options options() {
        return ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofMinutes(10))
                .build();
    }
}
