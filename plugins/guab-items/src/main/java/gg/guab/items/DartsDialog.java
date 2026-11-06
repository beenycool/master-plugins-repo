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

/**
 * The 26.3 dialog front-end for the dartboard. Right-clicking the board while
 * idle opens this, so a player can pick a leg without typing a command.
 */
public final class DartsDialog {

    private DartsDialog() {
    }

    public static void openMenu(GuabItemsPlugin plugin, Player player) {
        DartsStatsStore.Stats stats = plugin.darts().stats(player.getUniqueId());
        int balance = plugin.darts().balance(player.getUniqueId());

        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text(
                        "The board is bolted to the wall. Take a breath, pick a leg and throw.",
                        NamedTextColor.GRAY), 280),
                DialogBody.plainMessage(Component.text(
                        "Balance " + balance + " points   ·   Legs won " + stats.legsWon()
                                + "   ·   180s " + stats.oneEighties()
                                + "   ·   Best leg " + (stats.bestLegDarts() == 0 ? "—" : stats.bestLegDarts() + " darts"),
                        NamedTextColor.GOLD), 280));

        List<ActionButton> actions = new ArrayList<>();
        for (int mode : new int[]{101, 301, 501}) {
            actions.add(ActionButton.builder(Component.text(mode + " · " + modeName(mode), modeColour(mode)))
                    .tooltip(Component.text("Start a " + mode + " leg. Three darts per visit, double out."))
                    .width(110)
                    .action(DialogAction.customClick(
                            (DialogResponseView response, Audience audience) -> {
                                if (audience instanceof Player clicker) {
                                    plugin.getServer().getScheduler().runTask(plugin,
                                            () -> plugin.darts().startGame(clicker, mode,
                                                    plugin.darts().config().legsToWin()));
                                }
                            },
                            options()))
                    .build());
        }
        actions.add(ActionButton.builder(Component.text("My darts stats", NamedTextColor.AQUA))
                .tooltip(Component.text("Career legs, 180s, checkouts and best leg."))
                .width(150)
                .action(DialogAction.customClick(
                        (DialogResponseView response, Audience audience) -> {
                            if (audience instanceof Player clicker) {
                                plugin.getServer().getScheduler().runTask(plugin,
                                        () -> openStats(plugin, clicker));
                            }
                        },
                        options()))
                .build());

        player.showDialog(build(
                Component.text("The Wetherspoons Dartboard", NamedTextColor.GOLD),
                body,
                actions,
                Component.text("Not tonight", NamedTextColor.GRAY),
                2));
    }

    public static void openStats(GuabItemsPlugin plugin, Player player) {
        DartsStatsStore.Stats stats = plugin.darts().stats(player.getUniqueId());
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text(
                        "Legs played: " + stats.legsPlayed() + "   ·   Legs won: " + stats.legsWon(),
                        NamedTextColor.WHITE), 280),
                DialogBody.plainMessage(Component.text(
                        "Darts thrown: " + stats.dartsThrown() + "   ·   Best visit: " + stats.bestVisit(),
                        NamedTextColor.GRAY), 280),
                DialogBody.plainMessage(Component.text(
                        "180s: " + stats.oneEighties() + "   ·   Checkouts: " + stats.checkouts()
                                + "   ·   Highest checkout: " + stats.highestCheckout(),
                        NamedTextColor.GRAY), 280),
                DialogBody.plainMessage(Component.text(
                        "Best leg: " + (stats.bestLegDarts() == 0 ? "—" : stats.bestLegDarts() + " darts")
                                + "   ·   Best visit: " + stats.bestVisit(),
                        NamedTextColor.GOLD), 280));

        player.showDialog(build(
                Component.text("Your darts record", NamedTextColor.GOLD),
                body,
                List.of(ActionButton.builder(Component.text("Back to the board", NamedTextColor.YELLOW))
                        .width(160)
                        .action(DialogAction.customClick(
                                (DialogResponseView response, Audience audience) -> {
                                    if (audience instanceof Player clicker) {
                                        plugin.getServer().getScheduler().runTask(plugin,
                                                () -> openMenu(plugin, clicker));
                                    }
                                },
                                options()))
                        .build()),
                Component.text("Close", NamedTextColor.GRAY),
                1));
    }

    private static Dialog build(Component title, List<DialogBody> body, List<ActionButton> actions,
                                Component exitLabel, int columns) {
        ActionButton exit = ActionButton.builder(exitLabel)
                .width(100)
                .action(DialogAction.customClick(
                        (DialogResponseView response, Audience audience) -> audience.closeDialog(),
                        options()))
                .build();

        DialogBase base = DialogBase.builder(title)
                .externalTitle(title)
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

    private static ClickCallback.Options options() {
        return ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofMinutes(10))
                .build();
    }

    private static String modeName(int mode) {
        return switch (mode) {
            case 101 -> "quick pint";
            case 301 -> "proper leg";
            default -> "the full Spoons";
        };
    }

    private static NamedTextColor modeColour(int mode) {
        return switch (mode) {
            case 101 -> NamedTextColor.GREEN;
            case 301 -> NamedTextColor.YELLOW;
            default -> NamedTextColor.GOLD;
        };
    }
}
