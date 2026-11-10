package gg.guab.gambling;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BlackjackCommand implements CommandExecutor, TabCompleter {

    private final GuabGamblingPlugin plugin;
    private final Blackjack blackjack;
    private final BlackjackDealer dealer;
    private final BlackjackGui gui;

    public BlackjackCommand(GuabGamblingPlugin plugin, Blackjack blackjack, BlackjackDealer dealer, BlackjackGui gui) {
        this.plugin = plugin;
        this.blackjack = blackjack;
        this.dealer = dealer;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Blackjack is played in game.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("new")) {
            if (!openForPlay(player)) {
                return true;
            }
            newHand(player, args);
            return true;
        }
        if (args.length == 0) {
            if (!openForPlay(player)) {
                return true;
            }
            openGui(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "hit", "h" -> blackjack.hit(player);
            case "stand", "s", "stay" -> blackjack.stand(player);
            case "double", "d", "dd" -> blackjack.doubleDown(player);
            case "history", "log" -> {
                for (String line : blackjack.history(player)) {
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
                }
            }
            case "dealer", "setdealer" -> {
                if (!player.hasPermission("guab.casino.admin")) {
                    error(player, "You need guab.casino.admin to move the dealer.");
                    return true;
                }
                dealer.moveTo(player);
            }
            case "gui", "menu", "table" -> {
                if (!openForPlay(player)) {
                    return true;
                }
                openGui(player);
            }
            default -> {
                if (!openForPlay(player)) {
                    return true;
                }
                try {
                    int bet = Integer.parseInt(args[0]);
                    blackjack.start(player, bet);
                    openAfterStart(player);
                } catch (NumberFormatException exception) {
                    error(sender, "Usage: /bj [gui|<bet>|hit|stand|double|history]");
                }
            }
        }
        return true;
    }

    /** /new starts another hand at the same bet without retyping the amount. */
    private void newHand(Player player, String[] args) {
        if (blackjack.hasHand(player)) {
            info(player, "Finish this hand first: /bj hit, /bj stand or /bj double.");
            return;
        }
        int bet = blackjack.lastBet(player);
        if (args.length > 0) {
            try {
                bet = Integer.parseInt(args[0]);
            } catch (NumberFormatException exception) {
                error(player, "Usage: /new [bet]");
                return;
            }
        }
        blackjack.start(player, bet);
        openAfterStart(player);
    }

    private void openGui(Player player) {
        if (gui != null && plugin.getConfig().getBoolean("blackjack.gui.enabled", true)) {
            gui.open(player);
            return;
        }
        if (blackjack.hasHand(player)) {
            blackjack.showHand(player);
        } else {
            info(player, "Usage: /bj <bet> — then /bj hit, /bj stand, /bj double or /bj history.");
        }
    }

    private void openAfterStart(Player player) {
        if (gui != null && plugin.getConfig().getBoolean("blackjack.gui.enabled", true)
                && (blackjack.hasHand(player) || blackjack.result(player) != null)) {
            gui.open(player);
        }
    }

    /** New hands need both the event and the blackjack table to be open. */
    private boolean openForPlay(Player player) {
        if (!plugin.config().enabled()) {
            error(player, "The casino is closed. An admin can open it with /casino open.");
            return false;
        }
        if (!plugin.getConfig().getBoolean("blackjack.enabled", true)) {
            error(player, "Blackjack is closed.");
            return false;
        }
        return true;
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Blackjack] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Blackjack] ", NamedTextColor.RED)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (command.getName().equalsIgnoreCase("new")) {
                if (sender instanceof Player player) {
                    out.add(String.valueOf(blackjack.lastBet(player)));
                }
                return out;
            }
            if (sender instanceof Player player && blackjack.hasHand(player)) {
                out.addAll(List.of("hit", "stand", "double"));
            } else {
                out.add("10");
            }
            if (plugin.getConfig().getBoolean("blackjack.gui.enabled", true)) {
                out.addAll(List.of("gui", "menu"));
            }
            out.add("history");
            if (sender.hasPermission("guab.casino.admin")) {
                out.add("setdealer");
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(value -> !value.startsWith(prefix));
        return out;
    }
}
