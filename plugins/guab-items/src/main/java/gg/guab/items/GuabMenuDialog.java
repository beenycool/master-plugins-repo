package gg.guab.items;

import gg.guab.core.PointsService;
import gg.guab.core.StatsService;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The native 26.3 "Dialog OS" hub. Everything is read live from the existing
 * pub, darts, points and stats services.
 */
public final class GuabMenuDialog {

    private static final String TARGET_INPUT = "darts_target";

    private final GuabItemsPlugin plugin;

    public GuabMenuDialog(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    public void openHome(Player player) {
        int balance = balance(player);
        String pubLine = "Pub: " + plugin.pub().pints(player.getUniqueId()) + " pints and "
                + fmt(plugin.pub().drunkUnits(player.getUniqueId())) + " units right now.";
        List<DialogBody> body = List.of(
                plain("The Guab Arms. Pick a room; every button is a native dialog.",
                        NamedTextColor.GRAY),
                plain("Balance: " + balance + " points", NamedTextColor.GOLD),
                plain(pubLine, NamedTextColor.WHITE),
                plain("Pub · Casino · Darts · Parkour · Stats", NamedTextColor.AQUA));

        List<ActionButton> actions = List.of(
                page("Pub", NamedTextColor.GOLD, "Beer, milk and the drunk meter.", 160,
                        this::openPub),
                page("Casino", NamedTextColor.LIGHT_PURPLE,
                        "Enter the casino or read the blackjack rules.", 160, this::openCasino),
                page("Darts", NamedTextColor.YELLOW,
                        "Beginner-friendly 101 and 1v1 challenges.", 160, this::openDarts),
                page("Parkour", NamedTextColor.GREEN,
                        "Teleport to the parkour start.", 160,
                        clicker -> clicker.performCommand("parkour")),
                page("Stats", NamedTextColor.AQUA,
                        "Points, kills, deaths, rickrolls, parkour and darts.", 160, this::openStats));

        player.showDialog(GuabDialogFactory.build(
                Component.text("The Guab Arms", NamedTextColor.GOLD),
                body, actions, Component.text("Close", NamedTextColor.GRAY), 2));
    }

    public void openPub(Player player) {
        UUID id = player.getUniqueId();
        int balance = balance(player);
        double units = plugin.pub().drunkUnits(id);
        String tier = plugin.pub().tierLabel(units);

        List<DialogBody> body = List.of(
                plain("Charging is delegated to BeerService, so prices and points stay "
                        + "in one place.", NamedTextColor.GRAY),
                plain("Balance " + balance + " points   ·   Lifetime pints "
                        + plugin.pub().pints(id), NamedTextColor.GOLD),
                plain("Drunk meter: " + fmt(units) + " units   ·   " + tier, NamedTextColor.AQUA),
                plain("Branded pints show name · price · ABV · units. "
                        + "Milk clears the meter and the effects.", NamedTextColor.WHITE));

        List<ActionButton> actions = new ArrayList<>();
        for (BeerBrand brand : plugin.beer().allBrands()) {
            BeerStyle style = brand.style();
            Component label = Component.text(brand.displayName() + " · " + style.price() + " pt · "
                    + fmt(brand.abv()) + "% · " + brand.unitsLabel() + "u", style.colour());
            Component tooltip = Component.text(style.displayName() + ", " + brand.unitsLabel()
                    + " units, " + fmt(brand.abv()) + "% ABV"
                    + (style.lethal() ? " · ☠ Kills you." : ""));
            actions.add(GuabDialogFactory.button(plugin, label, tooltip, 280,
                    clicker -> plugin.beerService().purchase(clicker, brand, 1, style.price(), true)));
        }

        if (plugin.getConfig().getBoolean("beer.milk.enabled", true)) {
            int milkCost = Math.max(0, plugin.getConfig().getInt("beer.milk.cost", 5));
            actions.add(GuabDialogFactory.button(plugin,
                    Component.text("Milk · " + milkCost + " pt · Sober up", NamedTextColor.WHITE),
                    Component.text("Clears the drunk meter and all nausea/slowness effects."),
                    280,
                    clicker -> plugin.beerService().buyMilk(clicker, true)));
        }

        actions.add(page("Back to the hub", NamedTextColor.YELLOW, "Return to the main menu.", 180,
                this::openHome));

        player.showDialog(GuabDialogFactory.build(
                Component.text("The Wetherspoons Bar", NamedTextColor.GOLD),
                body, actions, Component.text("Close", NamedTextColor.GRAY), 2));
    }

    public void openCasino(Player player) {
        List<DialogBody> body = List.of(
                plain("The casino next door runs on the shared Guab points ledger.", NamedTextColor.GRAY),
                plain("Balance: " + balance(player) + " points", NamedTextColor.GOLD),
                plain("This page only launches the existing casino commands. "
                        + "Betting logic and payouts stay in GuabGambling.", NamedTextColor.WHITE),
                plain("Blackjack is chat-driven: start with /bj <bet>, then use "
                        + "/bj hit, /bj stand or /bj double.", NamedTextColor.AQUA));

        List<ActionButton> actions = List.of(
                page("Enter the casino", NamedTextColor.GOLD,
                        "Run /casino and teleport to the casino.", 200,
                        clicker -> clicker.performCommand("casino")),
                page("Casino status", NamedTextColor.YELLOW,
                        "Run /casino status.", 200,
                        clicker -> clicker.performCommand("casino status")),
                page("Blackjack · /bj", NamedTextColor.LIGHT_PURPLE,
                        "How the blackjack table works.", 200, this::openBlackjack),
                page("Back to the hub", NamedTextColor.YELLOW,
                        "Return to the main menu.", 200, this::openHome));

        player.showDialog(GuabDialogFactory.build(
                Component.text("The Guab Casino", NamedTextColor.GOLD),
                body, actions, Component.text("Close", NamedTextColor.GRAY), 2));
    }

    public void openBlackjack(Player player) {
        List<DialogBody> body = List.of(
                plain("The dealer stands on 17 and blackjack pays 3:2.", NamedTextColor.GRAY),
                plain("Start a hand with /bj <bet>. The dealer will show your cards and "
                        + "give you Hit, Stand and Double buttons in chat.", NamedTextColor.WHITE),
                plain("The table rejects bets outside its configured range and will not "
                        + "start a second hand while one is open.", NamedTextColor.AQUA),
                plain("Balance: " + balance(player) + " points", NamedTextColor.GOLD));

        List<ActionButton> actions = List.of(
                page("Enter the casino", NamedTextColor.GOLD,
                        "Run /casino and walk to the table.", 200,
                        clicker -> clicker.performCommand("casino")),
                page("Back to the casino page", NamedTextColor.YELLOW,
                        "Return to the casino hub.", 220, this::openCasino));

        player.showDialog(GuabDialogFactory.build(
                Component.text("Blackjack", NamedTextColor.LIGHT_PURPLE),
                body, actions, Component.text("Close", NamedTextColor.GRAY), 1));
    }

    public void openDarts(Player player) {
        DartsStatsStore.Stats stats = plugin.darts().stats(player.getUniqueId());
        boolean beginner = plugin.darts().config().beginnerAssist();
        List<DialogBody> body = List.of(
                plain("Three arrows per visit. "
                        + (beginner ? "Beginner assist is on: no busts and any dart can finish."
                                    : "Finish on a double.")
                        + " Pick a leg below; the board teleports you to the darts room.",
                        NamedTextColor.GRAY),
                plain("Record: legs " + stats.legsWon() + "/" + stats.legsPlayed()
                        + "   ·   180s " + stats.oneEighties()
                        + "   ·   checkouts " + stats.checkouts(), NamedTextColor.GOLD),
                plain("101 is the quickest game for new players. "
                        + "1v1 challenges use the online player list below.", NamedTextColor.WHITE));

        List<ActionButton> actions = new ArrayList<>();
        for (int mode : new int[]{101, 301, 501}) {
            actions.add(GuabDialogFactory.button(plugin,
                    Component.text(mode + " · " + modeName(mode), modeColour(mode)),
                    Component.text("Start a " + mode + " leg. "
                            + (beginner ? "No busts; any dart can finish."
                                        : "Three arrows per visit, double out.")),
                    160,
                    clicker -> plugin.darts().startGame(clicker, mode,
                            plugin.darts().config().legsToWin())));
        }

        List<DialogInput> inputs = List.of();
        List<SingleOptionDialogInput.OptionEntry> opponents = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(player.getUniqueId())) {
                opponents.add(SingleOptionDialogInput.OptionEntry.create(
                        online.getName(), Component.text(online.getName()), opponents.isEmpty()));
            }
        }
        if (!opponents.isEmpty()) {
            DialogInput targetInput = DialogInput.singleOption(TARGET_INPUT, 220, opponents,
                    Component.text("Challenge a player"), true);
            inputs = List.of(targetInput);
            actions.add(GuabDialogFactory.button(plugin,
                    Component.text("Challenge 1v1", NamedTextColor.GREEN),
                    Component.text("Send a 1v1 invite to the selected online player."),
                    200, this::challengeSelected));
        }

        actions.add(page("Detailed darts record", NamedTextColor.AQUA,
                "Legs, 180s, checkouts and your best leg.", 200,
                clicker -> DartsDialog.openStats(plugin, clicker)));
        actions.add(page("Back to the hub", NamedTextColor.YELLOW,
                "Return to the main menu.", 200, this::openHome));

        player.showDialog(GuabDialogFactory.build(
                Component.text("The Wetherspoons Dartboard", NamedTextColor.GOLD),
                body, inputs, actions, Component.text("Close", NamedTextColor.GRAY), 2));
    }

    public void openStats(Player player) {
        UUID id = player.getUniqueId();
        DartsStatsStore.Stats darts = plugin.darts().stats(id);
        String bestLeg = darts.bestLegDarts() == 0 ? "—" : darts.bestLegDarts() + " darts";
        List<DialogBody> body = List.of(
                plain("Points: " + balance(player), NamedTextColor.GOLD),
                plain("Deaths: " + stat(id, StatsService.DEATHS)
                        + "   ·   Kills: " + stat(id, StatsService.KILLS), NamedTextColor.WHITE),
                plain("Rickrolls: " + stat(id, StatsService.RICKROLLS)
                        + "   ·   Parkour: " + stat(id, StatsService.PARKOUR), NamedTextColor.AQUA),
                plain("Darts legs: " + darts.legsWon() + " won / " + darts.legsPlayed() + " played"
                        + "   ·   180s: " + darts.oneEighties(), NamedTextColor.YELLOW),
                plain("Checkouts: " + darts.checkouts()
                        + "   ·   Highest checkout: " + darts.highestCheckout()
                        + "   ·   Best leg: " + bestLeg, NamedTextColor.GRAY));

        List<ActionButton> actions = List.of(
                page("Detailed darts record", NamedTextColor.AQUA,
                        "Open the existing darts record dialog.", 220,
                        clicker -> DartsDialog.openStats(plugin, clicker)),
                page("Back to the hub", NamedTextColor.YELLOW,
                        "Return to the main menu.", 200, this::openHome));

        player.showDialog(GuabDialogFactory.build(
                Component.text("Your Guab record", NamedTextColor.GOLD),
                body, actions, Component.text("Close", NamedTextColor.GRAY), 1));
    }

    private void challengeSelected(Player player, DialogResponseView response) {
        if (!player.hasPermission("guab.pub.darts.use")) {
            message(player, "You are not allowed to play darts.", NamedTextColor.RED);
            return;
        }
        String targetName = response.getText(TARGET_INPUT);
        if (targetName == null || targetName.isBlank()) {
            message(player, "Pick an opponent first.", NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            message(player, "That player is no longer online.", NamedTextColor.RED);
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            message(player, "You cannot challenge yourself.", NamedTextColor.RED);
            return;
        }
        plugin.darts().challenge(player, target);
    }

    private ActionButton page(String label, NamedTextColor colour, String tooltip, int width,
                              Consumer<Player> action) {
        return GuabDialogFactory.button(plugin, Component.text(label, colour),
                Component.text(tooltip), width, action);
    }

    private static DialogBody plain(String text, NamedTextColor colour) {
        return DialogBody.plainMessage(Component.text(text, colour), 300);
    }

    private static void message(Player player, String text, NamedTextColor colour) {
        player.sendMessage(Component.text("[Guab] ", NamedTextColor.GOLD)
                .append(Component.text(text, colour)));
    }

    private int balance(Player player) {
        PointsService points = pointsService();
        return points == null ? 0 : points.get(player.getUniqueId());
    }

    private int stat(UUID player, String metric) {
        StatsService stats = statsService();
        return stats == null ? 0 : stats.get(player, metric);
    }

    private static PointsService pointsService() {
        var registration = Bukkit.getServicesManager().getRegistration(PointsService.class);
        return registration == null ? null : registration.getProvider();
    }

    static StatsService statsService() {
        var registration = Bukkit.getServicesManager().getRegistration(StatsService.class);
        return registration == null ? null : registration.getProvider();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
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
