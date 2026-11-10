package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
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
            case "tapinfo", "tap" -> tapInfo(sender);
            case "status", "where" -> status(sender);
            case "shelf", "shelves" -> shelf(sender, label, args);
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

    private void tapInfo(CommandSender sender) {
        Location tap = plugin.beerTap() == null ? null : plugin.beerTap().tapLocation();
        if (tap == null) {
            error(sender, "The tap world is not loaded (beer.tap.world).");
            return;
        }
        Block block = tap.getBlock();
        info(sender, "Tap: " + format(tap) + " · block " + block.getType()
                + (block.getType() == Material.BARREL ? " (barrel)" : " (NOT a barrel)")
                + " · radius " + plugin.getConfig().getDouble("beer.tap.radius", 12.0));
    }

    private void status(CommandSender sender) {
        Location centre = plugin.pub() == null ? null : plugin.pub().centre();
        Location tap = plugin.beerTap() == null ? null : plugin.beerTap().tapLocation();
        DartsConfig darts = plugin.darts().config();
        info(sender, "Pub centre: " + (centre == null ? "not loaded" : format(centre)
                + " · radius " + plugin.getConfig().getDouble("pub.round.radius", 14.0)));
        info(sender, "Tap: " + (tap == null ? "not loaded" : format(tap)));
        info(sender, "Board: " + darts.world() + " " + darts.x() + " " + darts.y() + " "
                + darts.z() + " facing " + darts.face() + " · enabled " + darts.enabled());
        List<String> shelves = plugin.getConfig().getStringList("pub.menu.shelves");
        info(sender, "Menu shelves: " + shelves.size());
        for (String shelf : shelves) {
            sender.sendMessage(Component.text("  · " + shelf, NamedTextColor.DARK_GRAY));
        }
        info(sender, "Worlds: pub=" + worldName(centre)
                + " tap=" + worldName(tap)
                + " board=" + darts.world()
                + " darts-room=" + (Bukkit.getWorld("guab_darts") == null ? "not created" : "guab_darts")
                + " leaderboard=" + plugin.getConfig().getString("pub.pints.leaderboard.world",
                        "guab_casino"));
        info(sender, "Beer menu enabled=" + plugin.getConfig().getBoolean("beer.menu.enabled", true)
                + " · quiz enabled=" + plugin.getConfig().getBoolean("pub.quiz.enabled", true)
                + " · " + plugin.pub().totalPints() + " pints served.");
    }

    private void shelf(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can target a shelf block.");
            return;
        }
        if (args.length < 2 || !(args[1].equalsIgnoreCase("add")
                || args[1].equalsIgnoreCase("remove"))) {
            error(sender, "Use /" + label + " shelf <add|remove> while looking at a block.");
            return;
        }
        Block target = player.getTargetBlockExact(8);
        if (target == null) {
            error(sender, "Look at the shelf block within 8 blocks.");
            return;
        }
        String key = target.getWorld().getName() + ";" + target.getX() + ";"
                + target.getY() + ";" + target.getZ();
        List<String> shelves = new ArrayList<>(plugin.getConfig().getStringList("pub.menu.shelves"));
        boolean add = args[1].equalsIgnoreCase("add");
        if (add && !shelves.contains(key)) {
            shelves.add(key);
        } else if (!add) {
            shelves.remove(key);
        } else {
            info(sender, "That block is already a menu shelf.");
            return;
        }
        plugin.getConfig().set("pub.menu.shelves", shelves);
        plugin.saveConfig();
        if (plugin.menuShelves() != null) {
            plugin.menuShelves().reload();
        }
        info(sender, (add ? "Added" : "Removed") + " menu shelf " + key + " (now "
                + shelves.size() + ").");
    }

    private static String format(Location location) {
        return location.getWorld().getName() + " " + location.getX() + " "
                + location.getY() + " " + location.getZ();
    }

    private static String worldName(Location location) {
        return location == null || location.getWorld() == null
                ? "not loaded" : location.getWorld().getName();
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(Component.text(
                "/" + label + " give <player> <wind_charge|jump_boost|beer[:brand|style]|milk> [amount]",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " reload | tapinfo | status", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " shelf <add|remove> (at the targeted block)",
                NamedTextColor.GRAY));
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
            return matching(List.of("give", "reload", "tapinfo", "status", "shelf"), args[0]);
        }
        if (args[0].equalsIgnoreCase("shelf") && args.length == 2) {
            return matching(List.of("add", "remove"), args[1]);
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
