package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GuabItemsCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "guab.items.admin";

    private final GuabItemsPlugin plugin;

    public GuabItemsCommand(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            error(sender, "You need " + PERMISSION + " to use this command.");
            return true;
        }
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, label, args);
            case "reload" -> reload(sender);
            default -> usage(sender, label);
        }
        return true;
    }

    private void give(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            usage(sender, label);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            error(sender, "Player not online: " + args[1]);
            return;
        }
        String rawType = args[2];
        String styleId = null;
        int colon = rawType.indexOf(':');
        if (colon > 0) {
            styleId = rawType.substring(colon + 1);
            rawType = rawType.substring(0, colon);
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException exception) {
                error(sender, "Amount must be a number.");
                return;
            }
        }
        amount = Math.clamp(amount, 1, plugin.config().maxGiveAmount());

        if (rawType.equalsIgnoreCase("milk") || rawType.equalsIgnoreCase("pint_of_milk")) {
            int remaining = amount;
            while (remaining-- > 0) {
                target.getInventory().addItem(plugin.items().createMilk()).values()
                        .forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
            }
            Component given = Component.text("Pint of Milk", NamedTextColor.WHITE);
            sender.sendMessage(prefix()
                    .append(Component.text("Gave " + amount + "x ", NamedTextColor.GRAY))
                    .append(given)
                    .append(Component.text(" to " + target.getName() + ".", NamedTextColor.GRAY)));
            if (!sender.equals(target)) {
                target.sendMessage(prefix()
                        .append(Component.text("You received " + amount + "x ", NamedTextColor.GRAY))
                        .append(given)
                        .append(Component.text(".", NamedTextColor.GRAY)));
            }
            return;
        }

        ItemType type = ItemType.byId(rawType);
        if (type == null) {
            error(sender, "Unknown item: " + args[2] + " (use wind_charge, jump_boost, beer, beer:<brand|style> or milk).");
            return;
        }
        BeerBrand beerBrand = null;
        if (type == ItemType.BEER) {
            beerBrand = styleId == null ? plugin.beer().houseBrand() : plugin.beer().brandFor(styleId);
            if (beerBrand == null) {
                error(sender, "Unknown beer: " + styleId + ". Try /beer list.");
                return;
            }
        }

        int remaining = amount;
        int stackSize = Math.max(1, type.material().getMaxStackSize());
        while (remaining > 0) {
            int batch = Math.min(remaining, stackSize);
            ItemStack stack = beerBrand == null
                    ? plugin.items().create(type, batch)
                    : plugin.items().createBeer(beerBrand, batch);
            target.getInventory().addItem(stack).values()
                    .forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
            remaining -= batch;
        }

        Component givenName = beerBrand == null
                ? type.displayName()
                : Component.text(beerBrand.displayName(), beerBrand.style().colour());
        sender.sendMessage(prefix()
                .append(Component.text("Gave " + amount + "x ", NamedTextColor.GRAY))
                .append(givenName)
                .append(Component.text(" to " + target.getName() + ".", NamedTextColor.GRAY)));
        if (!sender.equals(target)) {
            target.sendMessage(prefix()
                    .append(Component.text("You received " + amount + "x ", NamedTextColor.GRAY))
                    .append(givenName)
                    .append(Component.text(".", NamedTextColor.GRAY)));
        }
    }

    private void reload(CommandSender sender) {
        plugin.reload();
        info(sender, "Reloaded. Wind charge power=" + plugin.config().windChargePower()
                + ", radius=" + plugin.config().windChargeRadius()
                + ", jump boost amplifier=" + plugin.config().jumpBoostAmplifier() + ".");
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(Component.text(
                "/" + label + " give <player> <wind_charge|jump_boost|beer[:brand|style]|milk> [amount]",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " reload", NamedTextColor.GRAY));
    }

    private Component prefix() {
        return Component.text("[Items] ", NamedTextColor.GOLD);
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.GRAY)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(List.of("give", "reload"), args[0]);
        }
        if (!args[0].equalsIgnoreCase("give")) {
            return List.of();
        }
        if (args.length == 2) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return matching(names, args[1]);
        }
        if (args.length == 3) {
            List<String> types = new ArrayList<>();
            for (ItemType type : ItemType.values()) {
                types.add(type.id());
            }
            types.add("milk");
            for (BeerBrand brand : plugin.beer().allBrands()) {
                types.add("beer:" + brand.id());
            }
            for (BeerStyle style : plugin.beer().all()) {
                types.add("beer:" + style.id());
            }
            return matching(types, args[2]);
        }
        if (args.length == 4) {
            return matching(List.of("1", "8", "16", "32", "64"), args[3]);
        }
        return List.of();
    }

    private List<String> matching(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
