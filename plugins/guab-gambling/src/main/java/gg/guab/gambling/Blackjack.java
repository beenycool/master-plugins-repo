package gg.guab.gambling;

import gg.guab.core.HudChannel;
import gg.guab.core.PointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The blackjack table. Hands are settled against the Guab points ledger, so the
 * casino's Vault balance and the blackjack table always agree. The chat commands
 * remain available, but the dealer usually drives the chest inventory UI.
 */
public final class Blackjack implements Listener {

    private static final int HISTORY_LIMIT = 5;
    private static final long RESULT_TTL_MILLIS = 5 * 60_000L;

    /** A card as the UI should draw it. Hidden cards are the dealer's hole card. */
    public record CardView(String label, boolean red, boolean hidden) {
    }

    /** A read-only snapshot of an active hand for the inventory UI. */
    public record View(List<CardView> dealer, List<CardView> player, int dealerTotal,
                       int playerTotal, int bet, boolean canDouble, long expiresAt) {
    }

    /** The final state of a hand, kept briefly so the UI can show the result screen. */
    public record Result(String outcome, int bet, int payout, int playerTotal, int dealerTotal,
                         List<CardView> dealerCards, List<CardView> playerCards,
                         String message, long at) {
    }

    /** Called on the main thread whenever the visible table state changes. */
    public interface ViewListener {
        void onViewChanged(Player player);
    }

    private static final String[] RANKS = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};

    private static final String[] GREETINGS = {
            "Evening. The house is only slightly rigged.",
            "Take a seat. The carpet has seen worse.",
            "Cards are fresh. The odds are not.",
            "Table's open. Luck is optional.",
            "No rush. We have all night and your points."
    };

    private static final String[] HITS = {
            "Another one.",
            "Bold.",
            "Card.",
            "That's how it goes.",
            "The house thanks you."
    };

    private static final String[] BUSTS = {
            "Over. The house will take those.",
            "Bust. Happens to the optimistic.",
            "Too many. Next time, perhaps less.",
            "Gone."
    };

    private static final String[] NATURALS = {
            "Blackjack. Even a stopped clock.",
            "Twenty-one. The house pays, reluctantly.",
            "Natural. Enjoy it while it lasts."
    };

    private static final String[] WINS = {
            "Paid. Don't spend it all on the pub.",
            "The house can afford it. Barely.",
            "You win. We'll remember that.",
            "Nicely done. The ledger is lighter."
    };

    private static final String[] LOSSES = {
            "Dealer takes it.",
            "The house thanks you for your contribution.",
            "Close, but the house stands.",
            "Another round? The house is feeling generous."
    };

    private static final String[] PUSHES = {
            "Push. Nobody's happy.",
            "Same total. Your points go back.",
            "A draw. The house breaks even.",
            "Push. The carpet stays."
    };

    private final GuabGamblingPlugin plugin;
    private final Map<UUID, Hand> hands = new HashMap<>();
    private final Map<UUID, Deque<HandRecord>> history = new HashMap<>();
    private final Map<UUID, Result> lastResults = new HashMap<>();
    private final Deque<Card> shoe = new ArrayDeque<>();
    private final Map<UUID, Integer> lastBets = new HashMap<>();
    private final Random random = new Random();
    private ViewListener viewListener;
    private BukkitTask task;

    public Blackjack(GuabGamblingPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        PointsService points = points();
        if (points != null) {
            for (Hand hand : hands.values()) {
                points.add(hand.player, hand.bet);
            }
        }
        hands.clear();
        lastBets.clear();
        history.clear();
        lastResults.clear();
        viewListener = null;
    }

    public boolean hasHand(Player player) {
        return hands.containsKey(player.getUniqueId());
    }

    /** Number of hands currently waiting on a player action. */
    public int activeHands() {
        return hands.size();
    }

    /** Register the UI that should be refreshed whenever the visible table changes. */
    public void setViewListener(ViewListener listener) {
        this.viewListener = listener;
    }

    /** Current balance for the UI, or zero when the points ledger is unavailable. */
    public int balance(Player player) {
        PointsService points = points();
        return points == null ? 0 : points.get(player.getUniqueId());
    }

    /**
     * A snapshot of the active hand for the chest UI. The dealer's second card is
     * hidden, and the dealer total only counts the visible first card.
     */
    public View view(Player player) {
        Hand hand = hands.get(player.getUniqueId());
        if (hand == null) {
            return null;
        }
        List<CardView> dealer = new ArrayList<>(hand.dealer.size());
        for (int index = 0; index < hand.dealer.size(); index++) {
            Card card = hand.dealer.get(index);
            boolean hidden = index == 1;
            dealer.add(hidden
                    ? new CardView("??", false, true)
                    : new CardView(card.label(), isRed(card), false));
        }
        List<CardView> mine = new ArrayList<>(hand.cards.size());
        for (Card card : hand.cards) {
            mine.add(new CardView(card.label(), isRed(card), false));
        }
        int dealerVisible = hand.dealer.isEmpty() ? 0 : total(hand.dealer.subList(0, 1));
        return new View(
                List.copyOf(dealer),
                List.copyOf(mine),
                dealerVisible,
                total(hand.cards),
                hand.bet,
                hand.cards.size() == 2 && BetMath.canDouble(hand.bet),
                hand.expiresAt);
    }

    /** The most recent finished hand, kept for five minutes so the UI can show it. */
    public Result result(Player player) {
        Result stored = lastResults.get(player.getUniqueId());
        if (stored == null) {
            return null;
        }
        if (System.currentTimeMillis() - stored.at() > RESULT_TTL_MILLIS) {
            lastResults.remove(player.getUniqueId());
            return null;
        }
        return stored;
    }

    /** Drop the stored result, e.g. when the player returns to the bet screen. */
    public void clearResult(Player player) {
        lastResults.remove(player.getUniqueId());
    }

    /** The player's last blackjack bet, or the table minimum when they have not played yet. */
    public int lastBet(Player player) {
        int fallback = Math.max(1, plugin.getConfig().getInt("blackjack.min-bet", 10));
        return lastBets.getOrDefault(player.getUniqueId(), fallback);
    }

    /** Re-print the current hand and its action buttons (used by the dealer NPC). */
    public void showHand(Player player) {
        Hand hand = hands.get(player.getUniqueId());
        if (hand == null) {
            msg(player, "&eNo hand in play. Right-click the dealer to start one.", NamedTextColor.YELLOW);
            return;
        }
        player.sendMessage(handLine("Dealer: ", hand.dealer, true, total(hand.dealer.subList(0, 1))));
        player.sendMessage(handLine("You: ", hand.cards, false, total(hand.cards)));
        buttons(player, hand);
    }

    public void start(Player player, int bet) {
        if (!player.hasPermission("guab.casino.use")) {
            msg(player, "&cYou need guab.casino.use to play blackjack.", NamedTextColor.RED);
            return;
        }
        if (!plugin.getConfig().getBoolean("blackjack.enabled", true)) {
            msg(player, "&cBlackjack is closed.", NamedTextColor.RED);
            return;
        }
        if (!plugin.config().enabled()) {
            msg(player, "&cThe casino is closed.", NamedTextColor.RED);
            return;
        }
        if (hands.containsKey(player.getUniqueId())) {
            msg(player, "&eYou already have a hand. Use &f/bj hit&e, &f/bj stand&e or &f/bj double&e.", NamedTextColor.YELLOW);
            return;
        }
        int min = Math.max(1, plugin.getConfig().getInt("blackjack.min-bet", 10));
        int max = plugin.getConfig().getInt("blackjack.max-bet", -1);
        if (bet <= 0) {
            msg(player, "&cBet a positive number of points.", NamedTextColor.RED);
            return;
        }
        if (!BetMath.validBet(bet, min, max)) {
            msg(player, "&cBet between &6" + min + "&c and &6" + (max > 0 ? max : "no limit") + "&c points.", NamedTextColor.RED);
            return;
        }
        PointsService points = points();
        if (points == null) {
            msg(player, "&cThe points ledger is unavailable.", NamedTextColor.RED);
            return;
        }
        if (!points.take(player.getUniqueId(), bet)) {
            msg(player, "&cYou do not have enough points.", NamedTextColor.RED);
            return;
        }
        lastBets.put(player.getUniqueId(), bet);
        Hand hand = new Hand(player.getUniqueId(), player.getName(), bet);
        hand.cards.add(draw());
        hand.dealer.add(draw());
        hand.cards.add(draw());
        hand.dealer.add(draw());
        hand.expiresAt = System.currentTimeMillis() + timeoutMillis();
        hands.put(player.getUniqueId(), hand);

        msg(player, "&6Blackjack &7— bet &6" + bet + " &7points", NamedTextColor.GRAY);
        player.sendMessage(handLine("Dealer: ", hand.dealer, true, total(hand.dealer.subList(0, 1))));
        player.sendMessage(handLine("You: ", hand.cards, false, total(hand.cards)));
        msg(player, "&7Dealer: &f" + voice(GREETINGS), NamedTextColor.GRAY);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.7f, 1.2f);

        int playerTotal = total(hand.cards);
        int dealerTotal = total(hand.dealer);
        if (playerTotal == 21) {
            msg(player, "&6Blackjack!", NamedTextColor.GOLD);
            settle(hand);
            return;
        }
        buttons(player, hand);
        notifyView(player);
    }

    public void hit(Player player) {
        Hand hand = hands.get(player.getUniqueId());
        if (hand == null) {
            msg(player, "&cStart a hand with &f/bj <bet>&c.", NamedTextColor.RED);
            return;
        }
        hand.cards.add(draw());
        refreshTimeout(hand);
        player.sendMessage(handLine("You: ", hand.cards, false, total(hand.cards)));
        msg(player, "&7Dealer: &f" + voice(HITS), NamedTextColor.GRAY);
        int total = total(hand.cards);
        if (total > 21) {
            msg(player, "&cBust with " + total + ".", NamedTextColor.RED);
            settle(hand);
            return;
        }
        if (total == 21) {
            stand(player);
            return;
        }
        buttons(player, hand);
        notifyView(player);
    }

    public void doubleDown(Player player) {
        Hand hand = hands.get(player.getUniqueId());
        if (hand == null) {
            msg(player, "&cStart a hand with &f/bj <bet>&c.", NamedTextColor.RED);
            return;
        }
        if (hand.cards.size() != 2) {
            msg(player, "&cYou can only double on your first two cards.", NamedTextColor.RED);
            return;
        }
        if (!BetMath.canDouble(hand.bet)) {
            msg(player, "&cThat bet is too large to double.", NamedTextColor.RED);
            return;
        }
        PointsService points = points();
        if (points == null || !points.take(player.getUniqueId(), hand.bet)) {
            msg(player, "&cYou do not have enough points to double.", NamedTextColor.RED);
            return;
        }
        hand.doubled = true;
        hand.bet = BetMath.winPayout(hand.bet);
        hand.cards.add(draw());
        refreshTimeout(hand);
        msg(player, "&6Doubled to " + hand.bet + " points.", NamedTextColor.GOLD);
        player.sendMessage(handLine("You: ", hand.cards, false, total(hand.cards)));
        if (total(hand.cards) > 21) {
            msg(player, "&cBust with " + total(hand.cards) + ".", NamedTextColor.RED);
            settle(hand);
            return;
        }
        stand(player);
    }

    public void stand(Player player) {
        Hand hand = hands.get(player.getUniqueId());
        if (hand == null) {
            msg(player, "&cYou have no hand in play.", NamedTextColor.RED);
            return;
        }
        settle(hand);
    }

    /** Every action gets the full timeout window again, not one shared deadline. */
    private void refreshTimeout(Hand hand) {
        hand.expiresAt = System.currentTimeMillis() + timeoutMillis();
        hand.warned20 = false;
        hand.warned10 = false;
    }

    private static boolean isRed(Card card) {
        return card.suit == Suit.HEARTS || card.suit == Suit.DIAMONDS;
    }

    private static List<CardView> cardViews(List<Card> cards) {
        List<CardView> views = new ArrayList<>(cards.size());
        for (Card card : cards) {
            views.add(new CardView(card.label(), isRed(card), false));
        }
        return List.copyOf(views);
    }

    private void notifyView(Player player) {
        ViewListener listener = viewListener;
        if (listener == null || player == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            listener.onViewChanged(player);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> listener.onViewChanged(player));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Hand hand = hands.remove(event.getPlayer().getUniqueId());
        if (hand != null) {
            settleSilently(hand);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        lastResults.entrySet().removeIf(entry -> now - entry.getValue().at() > RESULT_TTL_MILLIS);
        for (UUID id : new ArrayList<>(hands.keySet())) {
            Hand hand = hands.get(id);
            if (hand == null) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            long remaining = hand.expiresAt - now;
            if (remaining > 0) {
                if (player != null && player.isOnline()) {
                    if (remaining <= 20_000 && remaining > 10_000 && !hand.warned20) {
                        hand.warned20 = true;
                        actionBar(player, Component.text("20s to act", NamedTextColor.YELLOW),
                                HudChannel.PRIORITY_TIMER, 30);
                    } else if (remaining <= 10_000 && !hand.warned10) {
                        hand.warned10 = true;
                        actionBar(player, Component.text("10s to act", NamedTextColor.RED),
                                HudChannel.PRIORITY_TIMER, 30);
                    }
                }
                continue;
            }
            if (player != null) {
                msg(player, "&eTime is up — standing automatically.", NamedTextColor.YELLOW);
            }
            settle(hand);
        }
    }

    private void settle(Hand hand) {
        hands.remove(hand.player);
        Player player = Bukkit.getPlayer(hand.player);
        int playerTotal = total(hand.cards);
        boolean natural = hand.cards.size() == 2 && playerTotal == 21;
        boolean dealerNatural = hand.dealer.size() == 2 && total(hand.dealer) == 21;

        if (!natural && playerTotal <= 21) {
            while (total(hand.dealer) < plugin.getConfig().getInt("blackjack.dealer-stands-on", 17)) {
                hand.dealer.add(draw());
            }
        }

        int dealerTotal = total(hand.dealer);
        int payout;
        String outcome;
        String result;
        String[] voice;
        if (playerTotal > 21) {
            payout = 0;
            outcome = "Bust";
            result = "&cBust — you lost " + hand.bet + " points.";
            voice = BUSTS;
        } else if (natural && !dealerNatural) {
            payout = BetMath.naturalPayout(hand.bet, plugin.getConfig().getDouble("blackjack.blackjack-payout", 1.5));
            outcome = "Blackjack";
            result = "&6Blackjack! You won " + (payout - hand.bet) + " points.";
            voice = NATURALS;
        } else if (dealerTotal > 21) {
            payout = BetMath.winPayout(hand.bet);
            outcome = "Win";
            result = "&aDealer bust — you won " + hand.bet + " points.";
            voice = WINS;
        } else if (playerTotal > dealerTotal) {
            payout = BetMath.winPayout(hand.bet);
            outcome = "Win";
            result = "&aYou won " + hand.bet + " points.";
            voice = WINS;
        } else if (playerTotal == dealerTotal) {
            payout = hand.bet;
            outcome = "Push";
            result = "&ePush — your " + hand.bet + " points are returned.";
            voice = PUSHES;
        } else {
            payout = 0;
            outcome = "Loss";
            result = "&cDealer wins — you lost " + hand.bet + " points.";
            voice = LOSSES;
        }
        boolean won = payout > hand.bet;

        if (payout > 0) {
            PointsService points = points();
            if (points != null) {
                points.add(hand.player, payout);
            }
        }
        recordHistory(hand, outcome, payout);
        lastResults.put(hand.player, new Result(
                outcome, hand.bet, payout, playerTotal, dealerTotal,
                cardViews(hand.dealer), cardViews(hand.cards), result, System.currentTimeMillis()));
        if (player != null) {
            player.sendMessage(handLine("Dealer: ", hand.dealer, false, dealerTotal));
            msg(player, result, NamedTextColor.WHITE);
            msg(player, "&7Dealer: &f" + voice(voice), NamedTextColor.GRAY);
            msg(player, "&7Balance: &6" + (points() == null ? "?" : points().get(hand.player)) + " &7| &f/bj <bet> &7to play again", NamedTextColor.GRAY);
            if (won) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.7f, 1.2f);
            } else if (payout == hand.bet) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 0.6f, 1.0f);
            } else {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.7f, 0.8f);
            }
        }
        if (won) {
            var world = Bukkit.getWorld(plugin.config().worldName());
            if (world != null) {
                Component broadcast = Component.text(hand.name, NamedTextColor.GOLD)
                        .append(Component.text(" won " + (payout - hand.bet) + " points at blackjack", NamedTextColor.GRAY));
                for (Player witness : world.getPlayers()) {
                    witness.sendMessage(broadcast);
                }
            }
        }
        notifyView(player);
    }

    private void settleSilently(Hand hand) {
        hands.remove(hand.player);
        int playerTotal = total(hand.cards);
        boolean natural = hand.cards.size() == 2 && playerTotal == 21;
        if (!natural && playerTotal <= 21) {
            while (total(hand.dealer) < plugin.getConfig().getInt("blackjack.dealer-stands-on", 17)) {
                hand.dealer.add(draw());
            }
        }
        int dealerTotal = total(hand.dealer);
        int payout = 0;
        String outcome;
        if (playerTotal > 21) {
            payout = 0;
            outcome = "Bust";
        } else if (natural && !(hand.dealer.size() == 2 && dealerTotal == 21)) {
            payout = BetMath.naturalPayout(hand.bet, plugin.getConfig().getDouble("blackjack.blackjack-payout", 1.5));
            outcome = "Blackjack";
        } else if (dealerTotal > 21 || playerTotal > dealerTotal) {
            payout = BetMath.winPayout(hand.bet);
            outcome = "Win";
        } else if (playerTotal == dealerTotal) {
            payout = hand.bet;
            outcome = "Push";
        } else {
            outcome = "Loss";
        }
        if (payout > 0 && points() != null) {
            points().add(hand.player, payout);
        }
        recordHistory(hand, outcome, payout);
    }

    private void recordHistory(Hand hand, String outcome, int payout) {
        Deque<HandRecord> records = history.computeIfAbsent(hand.player, id -> new ArrayDeque<>());
        records.addFirst(new HandRecord(outcome, hand.bet, payout, System.currentTimeMillis()));
        while (records.size() > HISTORY_LIMIT) {
            records.removeLast();
        }
    }

    /** The player's recent hands, newest first, in legacy {@code &} formatting. */
    public List<String> history(Player player) {
        Deque<HandRecord> records = history.get(player.getUniqueId());
        if (records == null || records.isEmpty()) {
            return List.of("&7No hands yet this session.");
        }
        List<String> out = new ArrayList<>(records.size());
        for (HandRecord record : records) {
            int net = record.payout() - record.bet();
            String colour = net > 0 ? "&a" : net < 0 ? "&c" : "&7";
            String sign = net > 0 ? "+" : "";
            out.add("&6" + record.outcome() + " &7· bet &f" + record.bet() + " &7· " + colour + sign + net);
        }
        return out;
    }

    private void buttons(Player player, Hand hand) {
        Component hit = Component.text("[Hit]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/bj hit"))
                .hoverEvent(HoverEvent.showText(Component.text("Draw another card", NamedTextColor.GRAY)));
        Component stand = Component.text("[Stand]", NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/bj stand"))
                .hoverEvent(HoverEvent.showText(Component.text("Hold your total", NamedTextColor.GRAY)));
        Component line = Component.text("Actions: ", NamedTextColor.GOLD)
                .append(hit)
                .append(Component.text("  ", NamedTextColor.GRAY))
                .append(stand);
        if (hand.cards.size() == 2) {
            line = line.append(Component.text("  ", NamedTextColor.GRAY))
                    .append(Component.text("[Double]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/bj double"))
                            .hoverEvent(HoverEvent.showText(Component.text("Double your bet for one more card", NamedTextColor.GRAY))));
        }
        player.sendMessage(line);
    }

    private Card draw() {
        if (shoe.size() < 15) {
            refill();
        }
        return shoe.pop();
    }

    private void refill() {
        List<Card> cards = new ArrayList<>();
        int decks = Math.max(1, plugin.getConfig().getInt("blackjack.decks", 6));
        for (int deck = 0; deck < decks; deck++) {
            for (String rank : RANKS) {
                for (Suit suit : Suit.values()) {
                    cards.add(new Card(rank, suit));
                }
            }
        }
        Collections.shuffle(cards, random);
        shoe.clear();
        shoe.addAll(cards);
    }

    private int total(List<Card> cards) {
        int total = 0;
        int aces = 0;
        for (Card card : cards) {
            if (card.rank.equals("A")) {
                total += 11;
                aces++;
            } else if (card.rank.equals("J") || card.rank.equals("Q") || card.rank.equals("K")) {
                total += 10;
            } else {
                total += Integer.parseInt(card.rank);
            }
        }
        while (total > 21 && aces > 0) {
            total -= 10;
            aces--;
        }
        return total;
    }

    private Component handLine(String label, List<Card> cards, boolean hideHole, int total) {
        return Component.text(label, NamedTextColor.GRAY)
                .append(cards(cards, hideHole))
                .append(Component.text(" (" + total + ")", NamedTextColor.DARK_GRAY));
    }

    private Component cards(List<Card> cards, boolean hideHole) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                out.append(' ');
            }
            if (hideHole && i == 1) {
                out.append("??");
            } else {
                out.append(cards.get(i).label());
            }
        }
        boolean red = cards.stream().anyMatch(card -> card.suit == Suit.HEARTS || card.suit == Suit.DIAMONDS);
        return Component.text(out.toString(), red ? NamedTextColor.RED : NamedTextColor.WHITE);
    }

    private long timeoutMillis() {
        return Math.max(10, plugin.getConfig().getInt("blackjack.timeout-seconds", 120)) * 1000L;
    }

    private PointsService points() {
        var registration = Bukkit.getServicesManager().getRegistration(PointsService.class);
        return registration == null ? null : registration.getProvider();
    }

    private String voice(String[] lines) {
        return lines[random.nextInt(lines.length)];
    }

    /** Countdowns outrank points and hints but never a critical game message. */
    private void actionBar(Player player, Component message, int priority, int durationTicks) {
        HudChannel channel = Bukkit.getServicesManager().load(HudChannel.class);
        if (channel != null) {
            channel.send(player, message, priority, durationTicks);
            return;
        }
        player.sendActionBar(message);
    }

    private void msg(Player player, String legacy, NamedTextColor fallback) {
        player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(legacy));
    }

    private enum Suit {
        SPADES("♠"), HEARTS("♥"), DIAMONDS("♦"), CLUBS("♣");

        private final String symbol;

        Suit(String symbol) {
            this.symbol = symbol;
        }
    }

    private record Card(String rank, Suit suit) {
        private String label() {
            return rank + suit.symbol;
        }
    }

    private record HandRecord(String outcome, int bet, int payout, long at) {
    }

    private static final class Hand {
        private final UUID player;
        private final String name;
        private final List<Card> cards = new ArrayList<>();
        private final List<Card> dealer = new ArrayList<>();
        private int bet;
        private boolean doubled;
        private boolean warned20;
        private boolean warned10;
        private long expiresAt;

        private Hand(UUID player, String name, int bet) {
            this.player = player;
            this.name = name;
            this.bet = bet;
        }
    }
}
