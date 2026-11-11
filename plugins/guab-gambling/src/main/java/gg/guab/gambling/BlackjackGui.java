package gg.guab.gambling;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The chest-based blackjack table. A 54-slot double chest gives the dealer enough
 * room for both card rows, the totals and the action buttons, while the chat
 * commands stay available as a fallback for players who prefer them.
 */
public final class BlackjackGui implements Listener {

    private static final int SIZE = 54;
    private static final int STATUS_SLOT = 4;
    private static final int CLOSE_SLOT = 53;
    private static final int DEAL_SLOT = 49;
    private static final int RESULT_SLOT = 22;
    private static final int MAX_CARD_SLOTS = 7;

    private static final int MIN_SLOT = 20;
    private static final int CURRENT_BET_SLOT = 22;
    private static final int REPEAT_SLOT = 24;

    private static final int[] CHIP_SLOTS = {37, 38, 39, 40, 41};
    private static final int[] CHIP_AMOUNTS = {100, 500, 1_000, 5_000, 10_000};
    private static final Material[] CHIP_MATERIALS = {
            Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK,
            Material.EMERALD, Material.DIAMOND
    };

    private static final int ALL_IN_SLOT = 43;
    private static final int RESET_SLOT = 47;
    private static final int BET_HISTORY_SLOT = 51;

    private static final int HIT_SLOT = 37;
    private static final int STAND_SLOT = 39;
    private static final int DOUBLE_SLOT = 41;
    private static final int TABLE_HISTORY_SLOT = 43;

    private static final int DEAL_AGAIN_SLOT = 38;
    private static final int CHANGE_BET_SLOT = 40;
    private static final int RESULT_HISTORY_SLOT = 42;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final GuabGamblingPlugin plugin;
    private final Blackjack blackjack;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public BlackjackGui(GuabGamblingPlugin plugin, Blackjack blackjack) {
        this.plugin = plugin;
        this.blackjack = blackjack;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Open the most relevant screen: table, last result, or the bet builder. */
    public void open(Player player) {
        if (blackjack.hasHand(player)) {
            openTable(player);
            return;
        }
        Blackjack.Result result = blackjack.result(player);
        if (result != null) {
            openResult(player, result);
            return;
        }
        openBet(player);
    }

    public void openBet(Player player) {
        openScreen(player, Screen.BET);
    }

    public void openTable(Player player) {
        openScreen(player, Screen.TABLE);
    }

    private void openResult(Player player, Blackjack.Result result) {
        openScreen(player, Screen.RESULT);
    }

    /**
     * Opens a fresh inventory so the window title matches the screen. Reusing the
     * inventory would leave a "Place your bet" title on the table after dealing.
     */
    private void openScreen(Player player, Screen screen) {
        int bet = screen == Screen.RESULT && blackjack.result(player) != null
                ? blackjack.result(player).bet()
                : blackjack.lastBet(player);
        Session session = newSession(player, screen, clampBet(player, bet));
        render(player, session);
        player.openInventory(session.holder.getInventory());
        click(player);
    }

    /** Called by {@link Blackjack} on the main thread whenever table state changes. */
    public void onViewChanged(Player player) {
        if (!player.isOnline()) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.holder != holder(player)) {
            sessions.remove(player.getUniqueId());
            return;
        }
        Screen target = blackjack.hasHand(player)
                ? Screen.TABLE
                : blackjack.result(player) != null ? Screen.RESULT : Screen.BET;
        if (target != session.screen && !session.pending) {
            // Swap one tick later so the inventory click that triggered the change has
            // fully finished. A fresh inventory also updates the window title.
            session.pending = true;
            UUID id = player.getUniqueId();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Session current = sessions.get(id);
                if (current == session && current.pending) {
                    current.pending = false;
                    Screen latest = blackjack.hasHand(player)
                            ? Screen.TABLE
                            : blackjack.result(player) != null ? Screen.RESULT : Screen.BET;
                    openScreen(player, latest);
                }
            });
            return;
        }
        render(player, session);
    }

    /** Repaint an already-open GUI after the table state changed. */
    public void refresh(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.holder != holder(player)) {
            return;
        }
        render(player, session);
    }

    public void closeAll() {
        for (Session session : new ArrayList<>(sessions.values())) {
            Player player = Bukkit.getPlayer(session.holder.owner);
            if (player != null && player.isOnline()
                    && player.getOpenInventory().getTopInventory().getHolder() == session.holder) {
                player.closeInventory();
            }
        }
        sessions.clear();
    }

    private Session newSession(Player player, Screen screen, int bet) {
        UUID id = player.getUniqueId();
        Holder holder = new Holder(id);
        holder.inventory = Bukkit.createInventory(holder, SIZE, title(screen));
        Session session = new Session(holder, screen, bet);
        sessions.put(id, session);
        return session;
    }

    private Component title(Screen screen) {
        return switch (screen) {
            case BET -> Component.text("Blackjack — Place your bet", NamedTextColor.DARK_GRAY);
            case TABLE -> Component.text("Blackjack — Table", NamedTextColor.DARK_GRAY);
            case RESULT -> Component.text("Blackjack — Result", NamedTextColor.DARK_GRAY);
        };
    }

    private InventoryHolder holder(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session == null ? null : session.holder;
    }

    private void render(Player player, Session session) {
        Inventory inventory = session.holder.getInventory();
        inventory.clear();

        if (session.screen == Screen.TABLE && blackjack.view(player) == null) {
            session.screen = blackjack.result(player) == null ? Screen.BET : Screen.RESULT;
        } else if (session.screen == Screen.RESULT && blackjack.result(player) == null) {
            session.screen = blackjack.hasHand(player) ? Screen.TABLE : Screen.BET;
        }

        switch (session.screen) {
            case BET -> {
                background(inventory, 1, 3);
                renderBet(player, session, inventory);
            }
            case TABLE -> {
                background(inventory, 2);
                renderTable(player, session, inventory);
            }
            case RESULT -> {
                background(inventory, 2);
                renderResult(player, session, inventory);
            }
        }
    }

    private void renderBet(Player player, Session session, Inventory inventory) {
        int min = minBet();
        int max = maxBet();
        int balance = blackjack.balance(player);
        String limit = max > 0 ? min + "–" + max : min + "+";
        Blackjack.Result last = blackjack.result(player);

        List<Component> status = new ArrayList<>();
        status.add(line("Balance: " + balance + " points", NamedTextColor.GOLD));
        status.add(line("Your bet: " + session.bet + " points", NamedTextColor.WHITE));
        status.add(line("Table limits: " + limit, NamedTextColor.GRAY));
        status.add(line("Blackjack pays " + payoutMultiplier() + ".", NamedTextColor.GRAY));
        if (last != null) {
            int net = last.payout() - last.bet();
            status.add(line("Last hand: " + last.outcome() + " (" + signed(net) + ")", net > 0
                    ? NamedTextColor.GREEN : net < 0 ? NamedTextColor.RED : NamedTextColor.GRAY));
        }
        status.add(Component.empty());
        status.add(line("Pick a chip to add. Right-click removes.", NamedTextColor.DARK_GRAY));
        inventory.setItem(STATUS_SLOT, display(Material.PAPER, Component.text("Blackjack", NamedTextColor.GOLD), status));

        inventory.setItem(MIN_SLOT, display(Material.GOLD_NUGGET, "&eMinimum bet",
                "&7Set the bet to &6" + min + " &7points."));
        inventory.setItem(CURRENT_BET_SLOT, display(Material.GOLD_BLOCK,
                Component.text("Bet: " + session.bet + " points", NamedTextColor.GOLD),
                List.of(line("Click a chip to add to this bet.", NamedTextColor.GRAY),
                        line("Table limits: " + limit, NamedTextColor.GRAY)), true));
        inventory.setItem(REPEAT_SLOT, display(Material.CLOCK, "&bRepeat last bet",
                "&7Your last stake: &6" + blackjack.lastBet(player) + "&7."));

        for (int index = 0; index < CHIP_SLOTS.length; index++) {
            inventory.setItem(CHIP_SLOTS[index], display(CHIP_MATERIALS[index],
                    Component.text("+" + CHIP_AMOUNTS[index], NamedTextColor.GOLD),
                    List.of(line("Left-click: add " + CHIP_AMOUNTS[index], NamedTextColor.GREEN),
                            line("Right-click: remove " + CHIP_AMOUNTS[index], NamedTextColor.RED),
                            line("Bet: " + session.bet, NamedTextColor.GRAY))));
        }

        inventory.setItem(ALL_IN_SLOT, display(Material.NETHER_STAR, "&6All in",
                "&7Bet your whole balance: &6" + balance + " &7points."));
        inventory.setItem(RESET_SLOT, display(Material.BARRIER, "&cReset bet",
                "&7Back to the table minimum: &6" + min + "&7."));
        inventory.setItem(DEAL_SLOT, display(Material.LIME_CONCRETE, Component.text("Deal", NamedTextColor.GREEN),
                List.of(line("Start a hand with " + session.bet + " points.", NamedTextColor.GRAY),
                        line("Blackjack pays " + payoutMultiplier() + ".", NamedTextColor.GRAY)), true));
        inventory.setItem(BET_HISTORY_SLOT, display(Material.BOOK, "&fHistory",
                "&7Your last five hands."));
        inventory.setItem(CLOSE_SLOT, display(Material.ARROW, "&fClose",
                "&7Leave the table without betting."));
    }

    private void renderTable(Player player, Session session, Inventory inventory) {
        Blackjack.View view = blackjack.view(player);
        if (view == null) {
            return;
        }
        int balance = blackjack.balance(player);

        List<Component> status = new ArrayList<>();
        status.add(line("Balance: " + balance + " points", NamedTextColor.GOLD));
        status.add(line("Bet: " + view.bet() + " points", NamedTextColor.WHITE));
        status.add(line("Dealer: " + view.dealerTotal()
                + (view.dealer().size() > 1 && view.dealer().get(1).hidden() ? " + ?" : ""), NamedTextColor.WHITE));
        status.add(line("You: " + view.playerTotal(), NamedTextColor.WHITE));
        status.add(Component.empty());
        status.add(line("Hit, stand or double to finish.", NamedTextColor.DARK_GRAY));
        inventory.setItem(STATUS_SLOT, display(Material.PAPER, Component.text("Blackjack", NamedTextColor.GOLD), status));

        inventory.setItem(9, display(Material.PAPER, "&7Dealer"));
        cards(inventory, 10, view.dealer());
        inventory.setItem(27, display(Material.PAPER, "&7You"));
        cards(inventory, 28, view.player());

        inventory.setItem(HIT_SLOT, display(Material.LIME_DYE, "&aHit",
                "&7Draw another card."));
        inventory.setItem(STAND_SLOT, display(Material.YELLOW_DYE, "&eStand",
                "&7Hold your total."));
        if (view.canDouble()) {
            inventory.setItem(DOUBLE_SLOT, display(Material.LIGHT_BLUE_DYE, "&bDouble",
                    "&7Double to &6" + BetMath.winPayout(view.bet()) + " &7points for one card."));
        } else {
            inventory.setItem(DOUBLE_SLOT, display(Material.GRAY_STAINED_GLASS_PANE, "&8Double",
                    "&7Only available on your first two cards."));
        }
        inventory.setItem(TABLE_HISTORY_SLOT, display(Material.BOOK, "&fHistory",
                "&7Your last five hands."));
        inventory.setItem(CLOSE_SLOT, display(Material.ARROW, "&fClose table",
                "&7Reopen the table without losing the hand."));
    }

    private void renderResult(Player player, Session session, Inventory inventory) {
        Blackjack.Result result = blackjack.result(player);
        if (result == null) {
            return;
        }
        session.bet = clampBet(player, result.bet());
        int balance = blackjack.balance(player);
        int net = result.payout() - result.bet();

        List<Component> status = new ArrayList<>();
        status.add(line("Balance: " + balance + " points", NamedTextColor.GOLD));
        status.add(line("Bet: " + result.bet() + " points", NamedTextColor.WHITE));
        status.add(line("Payout: " + result.payout() + " points", NamedTextColor.GRAY));
        status.add(line("Net: " + signed(net) + " points", net > 0
                ? NamedTextColor.GREEN : net < 0 ? NamedTextColor.RED : NamedTextColor.GRAY));
        inventory.setItem(STATUS_SLOT, display(Material.PAPER, Component.text("Blackjack", NamedTextColor.GOLD), status));

        inventory.setItem(9, display(Material.PAPER, "&7Dealer"));
        cards(inventory, 10, result.dealerCards());
        inventory.setItem(27, display(Material.PAPER, "&7You"));
        cards(inventory, 28, result.playerCards());
        inventory.setItem(RESULT_SLOT, resultIcon(result));

        inventory.setItem(DEAL_AGAIN_SLOT, display(Material.LIME_CONCRETE, "&aDeal again",
                "&7Another hand at &6" + result.bet() + " &7points."));
        inventory.setItem(CHANGE_BET_SLOT, display(Material.GOLD_INGOT, "&6Change bet",
                "&7Go back to the bet builder."));
        inventory.setItem(RESULT_HISTORY_SLOT, display(Material.BOOK, "&fHistory",
                "&7Your last five hands."));
        inventory.setItem(CLOSE_SLOT, display(Material.ARROW, "&fClose",
                "&7Leave the table."));
    }

    private ItemStack resultIcon(Blackjack.Result result) {
        Material material;
        NamedTextColor colour;
        switch (result.outcome()) {
            case "Blackjack" -> {
                material = Material.GOLD_BLOCK;
                colour = NamedTextColor.GOLD;
            }
            case "Win" -> {
                material = Material.LIME_CONCRETE;
                colour = NamedTextColor.GREEN;
            }
            case "Push" -> {
                material = Material.YELLOW_CONCRETE;
                colour = NamedTextColor.YELLOW;
            }
            default -> {
                material = Material.RED_CONCRETE;
                colour = NamedTextColor.RED;
            }
        }
        int net = result.payout() - result.bet();
        return display(material, Component.text(result.outcome(), colour),
                List.of(
                        LEGACY.deserialize(result.message()),
                        line("Payout: " + result.payout() + " points", NamedTextColor.GRAY),
                        line("Net: " + signed(net) + " points",
                                net > 0 ? NamedTextColor.GREEN : net < 0 ? NamedTextColor.RED : NamedTextColor.GRAY)),
                true);
    }

    private void cards(Inventory inventory, int startSlot, List<Blackjack.CardView> cards) {
        int shown = Math.min(cards.size(), MAX_CARD_SLOTS);
        for (int index = 0; index < shown; index++) {
            if (index == MAX_CARD_SLOTS - 1 && cards.size() > MAX_CARD_SLOTS) {
                int more = cards.size() - (MAX_CARD_SLOTS - 1);
                inventory.setItem(startSlot + index, display(Material.PAPER, "&7…",
                        "&7+" + more + " more cards"));
                break;
            }
            inventory.setItem(startSlot + index, card(cards.get(index)));
        }
    }

    private ItemStack card(Blackjack.CardView card) {
        if (card.hidden()) {
            return display(Material.GRAY_CONCRETE, Component.text("??", NamedTextColor.DARK_GRAY),
                    List.of(line("Dealer's hole card", NamedTextColor.GRAY)));
        }
        NamedTextColor colour = card.red() ? NamedTextColor.RED : NamedTextColor.WHITE;
        return display(Material.PAPER, Component.text(card.label(), colour), List.of());
    }

    private void background(Inventory inventory, int... dividerRows) {
        ItemStack edge = display(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        ItemStack filler = display(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            int row = slot / 9;
            inventory.setItem(slot, contains(dividerRows, row) ? edge : filler);
        }
    }

    private static boolean contains(int[] values, int value) {
        for (int candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    private static Component line(String text, NamedTextColor colour) {
        return Component.text(text, colour).decoration(TextDecoration.ITALIC, false);
    }

    private static String signed(int amount) {
        return amount > 0 ? "+" + amount : String.valueOf(amount);
    }

    private void handleClick(Player player, Session session, InventoryClickEvent event) {
        if (event.getRawSlot() < 0 || event.getRawSlot() >= SIZE) {
            return;
        }
        int slot = event.getRawSlot();
        boolean right = event.isRightClick();
        switch (session.screen) {
            case BET -> handleBetClick(player, session, slot, right);
            case TABLE -> handleTableClick(player, slot);
            case RESULT -> handleResultClick(player, session, slot);
        }
    }

    private void handleBetClick(Player player, Session session, int slot, boolean right) {
        for (int index = 0; index < CHIP_SLOTS.length; index++) {
            if (slot == CHIP_SLOTS[index]) {
                click(player);
                adjustBet(player, session, right ? -CHIP_AMOUNTS[index] : CHIP_AMOUNTS[index]);
                render(player, session);
                return;
            }
        }
        switch (slot) {
            case MIN_SLOT -> session.bet = minBet();
            case REPEAT_SLOT -> session.bet = clampBet(player, blackjack.lastBet(player));
            case ALL_IN_SLOT -> session.bet = clampBet(player, Math.max(minBet(), blackjack.balance(player)));
            case RESET_SLOT -> session.bet = minBet();
            case DEAL_SLOT -> {
                click(player);
                blackjack.start(player, session.bet);
                if (!blackjack.hasHand(player) && blackjack.result(player) != null) {
                    // A natural settles immediately; show its result instead of the bet screen.
                    session.screen = Screen.RESULT;
                }
                render(player, session);
                return;
            }
            case BET_HISTORY_SLOT -> {
                click(player);
                history(player);
                return;
            }
            case CLOSE_SLOT -> {
                click(player);
                player.closeInventory();
                return;
            }
            default -> {
                return;
            }
        }
        click(player);
        render(player, session);
    }

    private void handleTableClick(Player player, int slot) {
        switch (slot) {
            case HIT_SLOT -> {
                click(player);
                blackjack.hit(player);
            }
            case STAND_SLOT -> {
                click(player);
                blackjack.stand(player);
            }
            case DOUBLE_SLOT -> {
                Blackjack.View view = blackjack.view(player);
                if (view != null && view.canDouble()) {
                    click(player);
                    blackjack.doubleDown(player);
                }
            }
            case TABLE_HISTORY_SLOT -> {
                click(player);
                history(player);
            }
            case CLOSE_SLOT -> {
                click(player);
                player.closeInventory();
            }
            default -> {
            }
        }
    }

    private void handleResultClick(Player player, Session session, int slot) {
        Blackjack.Result result = blackjack.result(player);
        switch (slot) {
            case DEAL_AGAIN_SLOT -> {
                if (result == null) {
                    return;
                }
                click(player);
                session.bet = clampBet(player, result.bet());
                blackjack.start(player, session.bet);
                if (blackjack.hasHand(player)) {
                    blackjack.clearResult(player);
                } else if (blackjack.result(player) != null) {
                    // Natural settled immediately: keep showing the fresh result.
                    session.screen = Screen.RESULT;
                    render(player, session);
                } else {
                    session.screen = Screen.BET;
                    render(player, session);
                }
            }
            case CHANGE_BET_SLOT -> {
                click(player);
                if (result != null) {
                    session.bet = clampBet(player, result.bet());
                }
                blackjack.clearResult(player);
                session.screen = Screen.BET;
                render(player, session);
            }
            case RESULT_HISTORY_SLOT -> {
                click(player);
                history(player);
            }
            case CLOSE_SLOT -> {
                click(player);
                player.closeInventory();
            }
            default -> {
            }
        }
    }

    private void adjustBet(Player player, Session session, int delta) {
        long next = (long) session.bet + delta;
        session.bet = clampBet(player, (int) Math.max(1L, Math.min(next, Integer.MAX_VALUE)));
    }

    private int clampBet(Player player, int bet) {
        int floor = minBet();
        int ceiling = maxBet() > 0 ? maxBet() : Integer.MAX_VALUE;
        long value = Math.max((long) floor, Math.min((long) bet, ceiling));
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private int minBet() {
        return Math.max(1, plugin.getConfig().getInt("blackjack.min-bet", 10));
    }

    private int maxBet() {
        return plugin.getConfig().getInt("blackjack.max-bet", -1);
    }

    private double payoutMultiplier() {
        return plugin.getConfig().getDouble("blackjack.blackjack-payout", 1.5);
    }

    private void history(Player player) {
        for (String line : blackjack.history(player)) {
            player.sendMessage(LEGACY.deserialize(line));
        }
    }

    private void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.5f, 1.0f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.holder != holder) {
            return;
        }
        handleClick(player, session, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.holder == holder) {
            // Closing the window never touches the hand; it just drops the view.
            sessions.remove(player.getUniqueId());
        }
    }

    private ItemStack display(Material material, String name, String... lore) {
        return display(material, name, false, lore);
    }

    private ItemStack display(Material material, String name, boolean glow, String... lore) {
        List<Component> lines = new ArrayList<>(lore.length);
        for (String line : lore) {
            lines.add(LEGACY.deserialize(line));
        }
        return display(material, LEGACY.deserialize(name), lines, glow);
    }

    private ItemStack display(Material material, Component name, List<Component> lore) {
        return display(material, name, lore, false);
    }

    private ItemStack display(Material material, Component name, List<Component> lore, boolean glow) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain(name));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>(lore.size());
            for (Component line : lore) {
                lines.add(plain(line));
            }
            meta.lore(lines);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component plain(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private enum Screen {
        BET, TABLE, RESULT
    }

    private static final class Session {
        private final Holder holder;
        private Screen screen;
        private int bet;
        private boolean pending;

        private Session(Holder holder, Screen screen, int bet) {
            this.holder = holder;
            this.screen = screen;
            this.bet = bet;
        }
    }

    private static final class Holder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        private Holder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
