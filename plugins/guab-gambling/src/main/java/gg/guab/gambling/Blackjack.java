package gg.guab.gambling;

import gg.guab.core.PointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
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
 * Chat-driven blackjack table. Hands are settled against the Guab points ledger, so
 * the casino's Vault balance and the blackjack table always agree.
 */
public final class Blackjack implements Listener {

    private static final String[] RANKS = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};

    private final GuabGamblingPlugin plugin;
    private final Map<UUID, Hand> hands = new HashMap<>();
    private final Deque<Card> shoe = new ArrayDeque<>();
    private final Map<UUID, Integer> lastBets = new HashMap<>();
    private final Random random = new Random();
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
        hands.clear();
        lastBets.clear();
    }

    public boolean hasHand(Player player) {
        return hands.containsKey(player.getUniqueId());
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
        if (!plugin.getConfig().getBoolean("blackjack.enabled", true)) {
            msg(player, "&cBlackjack is closed.", NamedTextColor.RED);
            return;
        }
        if (hands.containsKey(player.getUniqueId())) {
            msg(player, "&eYou already have a hand. Use &f/bj hit&e, &f/bj stand&e or &f/bj double&e.", NamedTextColor.YELLOW);
            return;
        }
        int min = Math.max(1, plugin.getConfig().getInt("blackjack.min-bet", 10));
        int max = plugin.getConfig().getInt("blackjack.max-bet", -1);
        if (bet < min || (max > 0 && bet > max)) {
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

        int playerTotal = total(hand.cards);
        int dealerTotal = total(hand.dealer);
        if (playerTotal == 21) {
            msg(player, "&6Blackjack!", NamedTextColor.GOLD);
            settle(hand);
            return;
        }
        buttons(player, hand);
    }

    public void hit(Player player) {
        Hand hand = hands.get(player.getUniqueId());
        if (hand == null) {
            msg(player, "&cStart a hand with &f/bj <bet>&c.", NamedTextColor.RED);
            return;
        }
        hand.cards.add(draw());
        player.sendMessage(handLine("You: ", hand.cards, false, total(hand.cards)));
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
        PointsService points = points();
        if (points == null || !points.take(player.getUniqueId(), hand.bet)) {
            msg(player, "&cYou do not have enough points to double.", NamedTextColor.RED);
            return;
        }
        hand.doubled = true;
        hand.bet *= 2;
        hand.cards.add(draw());
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Hand hand = hands.remove(event.getPlayer().getUniqueId());
        if (hand != null) {
            settleSilently(hand);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(hands.keySet())) {
            Hand hand = hands.get(id);
            if (hand == null || now < hand.expiresAt) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
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

        if (!natural) {
            while (total(hand.dealer) < plugin.getConfig().getInt("blackjack.dealer-stands-on", 17)) {
                hand.dealer.add(draw());
            }
        }

        int dealerTotal = total(hand.dealer);
        int payout;
        String result;
        if (playerTotal > 21) {
            payout = 0;
            result = "&cBust — you lost " + hand.bet + " points.";
        } else if (natural && !dealerNatural) {
            payout = hand.bet + (int) Math.round(hand.bet * plugin.getConfig().getDouble("blackjack.blackjack-payout", 1.5));
            result = "&6Blackjack! You won " + (payout - hand.bet) + " points.";
        } else if (dealerTotal > 21) {
            payout = hand.bet * 2;
            result = "&aDealer bust — you won " + hand.bet + " points.";
        } else if (playerTotal > dealerTotal) {
            payout = hand.bet * 2;
            result = "&aYou won " + hand.bet + " points.";
        } else if (playerTotal == dealerTotal) {
            payout = hand.bet;
            result = "&ePush — your " + hand.bet + " points are returned.";
        } else {
            payout = 0;
            result = "&cDealer wins — you lost " + hand.bet + " points.";
        }

        if (payout > 0) {
            PointsService points = points();
            if (points != null) {
                points.add(hand.player, payout);
            }
        }
        if (player != null) {
            player.sendMessage(handLine("Dealer: ", hand.dealer, false, dealerTotal));
            msg(player, result, NamedTextColor.WHITE);
            msg(player, "&7Balance: &6" + (points() == null ? "?" : points().get(hand.player)) + " &7| &f/bj <bet> &7to play again", NamedTextColor.GRAY);
        }
    }

    private void settleSilently(Hand hand) {
        hands.remove(hand.player);
        int playerTotal = total(hand.cards);
        boolean natural = hand.cards.size() == 2 && playerTotal == 21;
        if (!natural) {
            while (total(hand.dealer) < plugin.getConfig().getInt("blackjack.dealer-stands-on", 17)) {
                hand.dealer.add(draw());
            }
        }
        int dealerTotal = total(hand.dealer);
        int payout = 0;
        if (playerTotal > 21) {
            payout = 0;
        } else if (natural && !(hand.dealer.size() == 2 && dealerTotal == 21)) {
            payout = hand.bet + (int) Math.round(hand.bet * plugin.getConfig().getDouble("blackjack.blackjack-payout", 1.5));
        } else if (dealerTotal > 21 || playerTotal > dealerTotal) {
            payout = hand.bet * 2;
        } else if (playerTotal == dealerTotal) {
            payout = hand.bet;
        }
        if (payout > 0 && points() != null) {
            points().add(hand.player, payout);
        }
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

    private static final class Hand {
        private final UUID player;
        private final String name;
        private final List<Card> cards = new ArrayList<>();
        private final List<Card> dealer = new ArrayList<>();
        private int bet;
        private boolean doubled;
        private long expiresAt;

        private Hand(UUID player, String name, int bet) {
            this.player = player;
            this.name = name;
            this.bet = bet;
        }
    }
}
