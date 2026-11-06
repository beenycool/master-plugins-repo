package gg.guab.gambling;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

    public BlackjackCommand(GuabGamblingPlugin plugin, Blackjack blackjack, BlackjackDealer dealer) {
        this.plugin = plugin;
        this.blackjack = blackjack;
        this.dealer = dealer;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Blackjack is played in game.");
            return true;
        }
        if (args.length == 0) {
            if (blackjack.hasHand(player)) {
                info(player, "You have a hand in play. Use /bj hit, /bj stand or /bj double.");
            } else {
                info(player, "Usage: /bj <bet> — then /bj hit, /bj stand or /bj double.");
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "hit", "h" -> blackjack.hit(player);
            case "stand", "s", "stay" -> blackjack.stand(player);
            case "double", "d", "dd" -> blackjack.doubleDown(player);
            case "dealer", "setdealer" -> {
                if (!player.hasPermission("guab.casino.admin")) {
                    error(player, "You need guab.casino.admin to move the dealer.");
                    return true;
                }
                dealer.moveTo(player);
            }
            default -> {
                try {
                    int bet = Integer.parseInt(args[0]);
                    blackjack.start(player, bet);
                } catch (NumberFormatException exception) {
                    error(sender, "Usage: /bj <bet> | /bj hit | /bj stand | /bj double");
                }
            }
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
            if (sender instanceof Player player && blackjack.hasHand(player)) {
                out.addAll(List.of("hit", "stand", "double"));
            } else {
                out.add("10");
            }
            if (sender.hasPermission("guab.casino.admin")) {
                out.add("setdealer");
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(value -> !value.startsWith(prefix));
        return out;
    }
}
