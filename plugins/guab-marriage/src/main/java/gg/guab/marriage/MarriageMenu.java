package gg.guab.marriage;

import gg.guab.core.PointsService;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The native 26.3 dialog UI for {@code /marry}. It is read-only over the store
 * and delegates every real action to {@link MarriageService}, so the chat
 * commands and the buttons cannot drift apart.
 */
public final class MarriageMenu {

    private static final String PROPOSE_INPUT = "marry_target";
    private static final int COUPLES_PER_PAGE = 8;

    private final GuabMarriagePlugin plugin;

    public MarriageMenu(GuabMarriagePlugin plugin) {
        this.plugin = plugin;
    }

    /** The dashboard: partner status and every action, or a propose picker. */
    public void open(Player player) {
        MarriageRecord record = plugin.store().find(player.getUniqueId());
        if (record == null) {
            openSingle(player);
        } else {
            openMarried(player, record);
        }
    }

    private void openSingle(Player player) {
        List<DialogBody> body = List.of(
                plain("You are not married. Bachelorship: intact.", NamedTextColor.WHITE),
                plain("Pick someone online below. They have "
                        + plugin.config().proposalTimeoutSeconds()
                        + " seconds to accept, or to fake a power cut.",
                        NamedTextColor.GRAY),
                plain("Wedding fee: " + plugin.config().weddingCost()
                        + " points from the proposer. No credit, no exceptions. "
                        + "Balance: " + balance(player) + " points.",
                        NamedTextColor.GOLD));

        List<SingleOptionDialogInput.OptionEntry> targets = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(player.getUniqueId())) {
                targets.add(SingleOptionDialogInput.OptionEntry.create(
                        online.getName(), Component.text(online.getName()), targets.isEmpty()));
            }
        }

        List<DialogInput> inputs = List.of();
        List<ActionButton> actions = new ArrayList<>();
        if (targets.isEmpty()) {
            body = new ArrayList<>(body);
            body.add(plain("Nobody else is online. The pub is empty and the register is closed.", NamedTextColor.RED));
        } else {
            inputs = List.of(DialogInput.singleOption(PROPOSE_INPUT, 220, targets,
                    Component.text("Propose to an online player"), true));
            actions.add(button(Component.text("Send proposal", NamedTextColor.GREEN),
                    Component.text("Propose to the selected player."), 200,
                    (clicker, response) -> {
                        String target = response.getText(PROPOSE_INPUT);
                        if (target == null || target.isBlank()) {
                            MarriageMessages.error(clicker, "Pick a player first. Telepathy is not supported.");
                            return;
                        }
                        clicker.performCommand("marry " + target);
                    }));
        }
        actions.add(page("Married couples", NamedTextColor.YELLOW,
                "See who is already married.", 200, clicker -> openCouples(clicker, 1)));
        actions.add(page("How it works", NamedTextColor.AQUA,
                "Commands, fees, homes and couple chat.", 200, this::openHelp));

        player.showDialog(build(Component.text("Guab Marriage", NamedTextColor.LIGHT_PURPLE),
                body, inputs, actions, 2));
    }

    private void openMarried(Player player, MarriageRecord record) {
        String partnerName = record.otherName(player.getUniqueId());
        Player partner = Bukkit.getPlayer(record.other(player.getUniqueId()));
        String home = record.home() == null ? "not set yet"
                : record.home().world() + " " + String.format(Locale.ROOT, "%.1f, %.1f, %.1f",
                        record.home().x(), record.home().y(), record.home().z());

        List<DialogBody> body = List.of(
                plain("Partner: " + (partnerName.isBlank() ? "unknown" : partnerName)
                                + (partner == null
                                        ? " (offline). Freedom, briefly."
                                        : " (online). Still putting up with you."),
                        NamedTextColor.LIGHT_PURPLE),
                plain("Married since " + MarriageRecord.formatDate(record.since())
                                + " — " + record.daysLabel(System.currentTimeMillis())
                                + " of shared chat and shared blame.",
                        NamedTextColor.WHITE),
                plain("Shared home: " + home + (record.home() == null
                        ? ". The floor is yours." : ". Mind the cobwebs."), NamedTextColor.AQUA),
                plain("Spouse PvP: " + (record.pvp() ? "on. You may bonk." : "off. Blessed peace.")
                                + "   ·   Balance: " + balance(player) + " points",
                        NamedTextColor.GOLD));

        List<ActionButton> actions = new ArrayList<>();
        if (record.home() != null) {
            actions.add(page("Couple home", NamedTextColor.GREEN,
                    "Teleport to the shared home.", 200,
                    clicker -> run(clicker, "marry home")));
        }
        actions.add(page("Teleport to partner", NamedTextColor.AQUA,
                "Join your partner where they are.", 200,
                clicker -> run(clicker, "marry tp")));
        actions.add(page("Kiss", NamedTextColor.LIGHT_PURPLE,
                "Hearts, a bell and a short cooldown.", 200,
                clicker -> run(clicker, "marry kiss")));
        actions.add(page("Gift held item", NamedTextColor.YELLOW,
                "Hand your main-hand stack to your partner.", 200,
                clicker -> run(clicker, "marry gift")));
        actions.add(page("Wedding ring", NamedTextColor.GOLD,
                "Claim the cosmetic ring from the resource pack.", 200,
                clicker -> run(clicker, "marry ring")));
        actions.add(page("PvP: " + (record.pvp() ? "on" : "off"),
                record.pvp() ? NamedTextColor.RED : NamedTextColor.GREEN,
                "Toggle damage between you and your partner.", 200,
                clicker -> {
                    plugin.service().pvp(clicker, record.pvp() ? "off" : "on");
                    plugin.getServer().getScheduler().runTask(plugin, () -> open(clicker));
                }));
        actions.add(page("Set home here", NamedTextColor.GREEN,
                "Save where you are standing as the shared home.", 200,
                clicker -> run(clicker, "marry sethome")));
        actions.add(page("Married couples", NamedTextColor.YELLOW,
                "See every couple on the server.", 200,
                clicker -> openCouples(clicker, 1)));
        actions.add(page("Divorce", NamedTextColor.DARK_RED,
                "Open the confirmation page (costs "
                        + plugin.config().divorceCost() + " points).", 200,
                clicker -> openDivorce(clicker, record)));

        player.showDialog(build(Component.text("Guab Marriage · " + partnerName,
                        NamedTextColor.LIGHT_PURPLE),
                body, List.of(), actions, 3));
    }

    /** A paginated list of every couple. */
    public void openCouples(Player player, int requestedPage) {
        List<MarriageRecord> records = new ArrayList<>(plugin.store().all());
        if (records.isEmpty()) {
            List<DialogBody> body = List.of(
                    plain("No couples yet. The register office is suspiciously quiet.", NamedTextColor.WHITE),
                    plain("Be the first. Someone has to go first.",
                            NamedTextColor.GRAY));
            player.showDialog(build(Component.text("Married couples", NamedTextColor.YELLOW),
                    body, List.of(),
                    List.of(page("Back to marriage", NamedTextColor.LIGHT_PURPLE,
                            "Return to your marriage page.", 220, this::open)), 1));
            return;
        }
        records.sort(Comparator.comparing((MarriageRecord record) ->
                        record.firstName().toLowerCase(Locale.ROOT))
                .thenComparing(record -> record.secondName().toLowerCase(Locale.ROOT)));

        int pages = Math.max(1, (records.size() + COUPLES_PER_PAGE - 1) / COUPLES_PER_PAGE);
        int page = Math.clamp(requestedPage, 1, pages);
        int from = (page - 1) * COUPLES_PER_PAGE;
        int to = Math.min(records.size(), from + COUPLES_PER_PAGE);
        long now = System.currentTimeMillis();

        List<DialogBody> body = new ArrayList<>();
        body.add(plain(records.size() + " couple(s) — page " + page + "/" + pages + ". Gossip below:",
                NamedTextColor.GRAY));
        for (int index = from; index < to; index++) {
            MarriageRecord record = records.get(index);
            body.add(plain((index + 1) + ". " + record.firstName() + " & "
                            + record.secondName() + " — " + record.daysLabel(now),
                    NamedTextColor.WHITE));
        }

        List<ActionButton> actions = new ArrayList<>();
        if (pages > 1) {
            actions.add(page("Previous", NamedTextColor.YELLOW,
                    "Go to page " + (page > 1 ? page - 1 : pages) + ".", 160,
                    clicker -> openCouples(clicker, page > 1 ? page - 1 : pages)));
            actions.add(page("Next", NamedTextColor.YELLOW,
                    "Go to page " + (page < pages ? page + 1 : 1) + ".", 160,
                    clicker -> openCouples(clicker, page < pages ? page + 1 : 1)));
        }
        actions.add(page("Back to marriage", NamedTextColor.LIGHT_PURPLE,
                "Return to your marriage page.", 220, this::open));

        player.showDialog(build(Component.text("Married couples", NamedTextColor.YELLOW),
                body, List.of(), actions, 2));
    }

    private void openDivorce(Player player, MarriageRecord record) {
        String partnerName = record.otherName(player.getUniqueId());
        List<DialogBody> body = List.of(
                plain("Divorce " + partnerName + "? Bold.", NamedTextColor.DARK_RED),
                plain("This removes the marriage and charges "
                        + plugin.config().divorceCost() + " points. The shared home, "
                        + "ring and couple chat go with it. So does the tax break "
                        + "(there is not one).", NamedTextColor.GRAY),
                plain("This cannot be undone. Not even with a pint.", NamedTextColor.RED));

        List<ActionButton> actions = List.of(
                page("Confirm divorce", NamedTextColor.DARK_RED,
                        "End the marriage now.", 220,
                        clicker -> {
                            plugin.service().divorceNow(clicker);
                            plugin.getServer().getScheduler().runTask(plugin, () -> open(clicker));
                        }),
                page("Keep the marriage", NamedTextColor.GREEN,
                        "Go back without changing anything.", 220, this::open));

        player.showDialog(build(Component.text("Confirm divorce", NamedTextColor.DARK_RED),
                body, List.of(), actions, 2));
    }

    private void openHelp(Player player) {
        List<DialogBody> body = List.of(
                plain("/marry <player> — propose. The target clicks Accept or Deny.",
                        NamedTextColor.WHITE),
                plain("/marry accept|deny [player] — answer a proposal.", NamedTextColor.WHITE),
                plain("/marry info|list|anniversary — your partner, every couple and the date.",
                        NamedTextColor.WHITE),
                plain("/marry tp|sethome|home — travel together.", NamedTextColor.WHITE),
                plain("/marry gift|kiss|ring — share an item, hearts and a cosmetic ring.",
                        NamedTextColor.WHITE),
                plain("/marry pvp [on|off] — allow or block damage between you.",
                        NamedTextColor.WHITE),
                plain("/marry divorce — confirmation page, costs "
                        + plugin.config().divorceCost() + " points.", NamedTextColor.WHITE),
                plain("/mc <message> — private couple chat.", NamedTextColor.WHITE),
                plain("/marry menu — reopen this UI.", NamedTextColor.GOLD));

        List<ActionButton> actions = List.of(
                page("Back to marriage", NamedTextColor.LIGHT_PURPLE,
                        "Return to your marriage page.", 220, this::open),
                page("Married couples", NamedTextColor.YELLOW,
                        "See who is already married.", 220,
                        clicker -> openCouples(clicker, 1)));

        player.showDialog(build(Component.text("Marriage help", NamedTextColor.AQUA),
                body, List.of(), actions, 2));
    }

    // ------------------------------------------------------------------ dialog

    private Dialog build(Component title, List<DialogBody> body, List<DialogInput> inputs,
                         List<ActionButton> actions, int columns) {
        ActionButton exit = ActionButton.builder(Component.text("Close", NamedTextColor.GRAY))
                .width(120)
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
                .inputs(inputs)
                .build();

        return Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.multiAction(actions, exit, columns)));
    }

    private ActionButton page(String label, NamedTextColor colour, String tooltip, int width,
                              Consumer<Player> action) {
        return button(Component.text(label, colour), Component.text(tooltip), width,
                (player, response) -> action.accept(player));
    }

    private ActionButton button(Component label, Component tooltip, int width,
                                BiConsumer<Player, DialogResponseView> action) {
        var builder = ActionButton.builder(label).width(width);
        if (tooltip != null) {
            builder.tooltip(tooltip);
        }
        return builder.action(DialogAction.customClick(
                        (DialogResponseView response, Audience audience) -> {
                            if (audience instanceof Player player) {
                                plugin.getServer().getScheduler().runTask(plugin,
                                        () -> action.accept(player, response));
                            }
                        },
                        options()))
                .build();
    }

    private static ClickCallback.Options options() {
        return ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofMinutes(10))
                .build();
    }

    private void run(Player player, String command) {
        player.performCommand(command);
    }

    private long balance(Player player) {
        var registration = Bukkit.getServicesManager().getRegistration(PointsService.class);
        return registration == null ? 0L : registration.getProvider().get(player.getUniqueId());
    }

    private static DialogBody plain(String text, NamedTextColor colour) {
        return DialogBody.plainMessage(Component.text(text, colour), 300);
    }
}
