package gg.guab.marriage;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Argument parsing for {@code /marry} and {@code /mc}. All real decisions are
 * delegated to {@link MarriageService}; bad input always gets a message and a
 * normal return rather than an exception.
 */
public final class MarriageCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "menu", "help", "accept", "deny", "info", "list", "divorce", "tp", "sethome", "home",
            "gift", "kiss", "pvp", "ring", "anniversary", "reload", "admin");

    private final GuabMarriagePlugin plugin;

    public MarriageCommand(GuabMarriagePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("mc")) {
            chat(sender, args);
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                if (!player.hasPermission("guab.marriage.use")) {
                    MarriageMessages.error(player, "You need guab.marriage.use. Marriage is a members-only club.");
                    return true;
                }
                plugin.menu().open(player);
            } else {
                usage(sender);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload") || sub.equals("admin")) {
            if (!sender.hasPermission("guab.marriage.admin")) {
                MarriageMessages.error(sender, "You need guab.marriage.admin. This is above your pay grade.");
                return true;
            }
            if (sub.equals("reload")) {
                reload(sender);
            } else {
                admin(sender, args);
            }
            return true;
        }
        if (!sender.hasPermission("guab.marriage.use")) {
            MarriageMessages.error(sender, "You need guab.marriage.use. Marriage is a members-only club.");
            return true;
        }
        if (!SUBCOMMANDS.contains(sub)) {
            Player player = player(sender);
            if (player != null) {
                plugin.service().propose(player, args[0]);
            }
            return true;
        }

        switch (sub) {
            case "menu" -> {
                Player player = player(sender);
                if (player != null) {
                    plugin.menu().open(player);
                }
            }
            case "help" -> usage(sender);
            case "accept", "deny" -> {
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                String name = args.length > 1 ? args[1] : null;
                if (sub.equals("accept")) {
                    plugin.service().accept(player, name);
                } else {
                    plugin.service().deny(player, name);
                }
            }
            case "info" -> plugin.service().showInfo(sender, args.length > 1 ? args[1] : null);
            case "list" -> {
                int page = 1;
                if (args.length > 1) {
                    try {
                        page = Integer.parseInt(args[1]);
                    } catch (NumberFormatException exception) {
                        MarriageMessages.error(sender, "Page must be a number. The register is not sorted by vibes.");
                        return true;
                    }
                }
                plugin.service().list(sender, page);
            }
            case "divorce" -> {
                Player player = player(sender);
                if (player == null) {
                    return true;
                }
                if (args.length > 1 && !args[1].equalsIgnoreCase("confirm")) {
                    MarriageMessages.error(sender, "Usage: /marry divorce [confirm]. One does not simply divorce.");
                    return true;
                }
                plugin.service().divorce(player, args.length > 1);
            }
            case "tp" -> {
                Player player = player(sender);
                if (player != null) {
                    plugin.service().teleportToPartner(player);
                }
            }
            case "sethome" -> {
                Player player = player(sender);
                if (player != null) {
                    plugin.service().setHome(player);
                }
            }
            case "home" -> {
                Player player = player(sender);
                if (player != null) {
                    plugin.service().teleportHome(player);
                }
            }
            case "gift" -> {
                Player player = player(sender);
                if (player != null) {
                    plugin.service().gift(player);
                }
            }
            case "kiss" -> {
                Player player = player(sender);
                if (player != null) {
                    plugin.service().kiss(player);
                }
            }
            case "pvp" -> {
                Player player = player(sender);
                if (player != null) {
                    plugin.service().pvp(player, args.length > 1 ? args[1] : null);
                }
            }
            case "ring" -> {
                Player player = player(sender);
                if (player != null) {
                    plugin.service().ring(player);
                }
            }
            case "anniversary" -> {
                Player player = player(sender);
                if (player != null) {
                    plugin.service().anniversary(player);
                }
            }
            default -> usage(sender);
        }
        return true;
    }

    private void chat(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MarriageMessages.error(sender, "Only players can use couple chat. The console has no partner.");
            return;
        }
        if (!player.hasPermission("guab.marriage.use")) {
            MarriageMessages.error(player, "You need guab.marriage.use. Couple chat is a members-only club.");
            return;
        }
        if (args.length == 0) {
            MarriageMessages.error(player, "Usage: /mc <message>. Private, but not that private.");
            return;
        }
        plugin.service().chat(player, String.join(" ", args));
    }

    private void admin(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("divorce")) {
            MarriageMessages.error(sender, "Usage: /marry admin divorce <player> — no ceremony, no fee, no tears.");
            return;
        }
        plugin.service().adminDivorce(sender, args[2]);
    }

    private void reload(CommandSender sender) {
        boolean loaded = plugin.reload();
        if (loaded) {
            int couples = plugin.store() == null ? 0 : plugin.store().size();
            MarriageMessages.info(sender, "Reloaded config.yml and marriages.yml (" + couples
                    + " couple(s)). Nobody noticed.");
        } else {
            int couples = plugin.store() == null ? 0 : plugin.store().size();
            MarriageMessages.error(sender, "config.yml reloaded, but marriages.yml was missing or malformed. "
                    + "Kept the " + couples + " couple(s) already in memory.");
        }
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        MarriageMessages.error(sender, "Only players can do that. The console is forever single.");
        return null;
    }

    private void usage(CommandSender sender) {
        MarriageMessages.info(sender, "/marry - open the marriage menu (no commitment)");
        MarriageMessages.info(sender, "/marry <player> - propose. They get 60 seconds to reconsider.");
        MarriageMessages.info(sender, "/marry accept|deny [player] - accept or deny. Choose wisely.");
        MarriageMessages.info(sender, "/marry info [player] - partner, date, home and PvP. The paperwork.");
        MarriageMessages.info(sender, "/marry list [page] - every couple. Bring gossip.");
        MarriageMessages.info(sender, "/marry divorce [confirm] - end it. The pub will pretend not to hear.");
        MarriageMessages.info(sender, "/marry tp|home|sethome - travel together, or flee dramatically.");
        MarriageMessages.info(sender, "/marry gift|kiss|ring - share an item, a moment, or a keepsake.");
        MarriageMessages.info(sender, "/marry pvp [on|off] - allow or block spouse damage. Consent matters.");
        MarriageMessages.info(sender, "/marry anniversary - days married and the date. No card required.");
        MarriageMessages.info(sender, "/mc <message> - private message. The pub cannot hear you.");
        if (sender.hasPermission("guab.marriage.admin")) {
            MarriageMessages.info(sender, "/marry reload | /marry admin divorce <player> - admin business.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        try {
            if (command.getName().equalsIgnoreCase("mc")) {
                return List.of();
            }
            if (args.length == 1) {
                Set<String> options = new LinkedHashSet<>(SUBCOMMANDS);
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (!(sender instanceof Player player) || !online.getUniqueId().equals(player.getUniqueId())) {
                        options.add(online.getName());
                    }
                }
                return filter(options, args[0]);
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2) {
                return switch (sub) {
                    case "accept", "deny" -> filter(proposerNames(sender), args[1]);
                    case "info" -> filter(knownNames(), args[1]);
                    case "admin" -> filter(List.of("divorce"), args[1]);
                    case "pvp" -> filter(List.of("on", "off", "toggle"), args[1]);
                    case "divorce" -> filter(List.of("confirm"), args[1]);
                    case "list" -> filter(List.of("1", "2", "3"), args[1]);
                    default -> List.of();
                };
            }
            if (args.length == 3 && sub.equals("admin") && args[1].equalsIgnoreCase("divorce")) {
                return filter(knownNames(), args[2]);
            }
            return List.of();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private List<String> proposerNames(CommandSender sender) {
        if (!(sender instanceof Player player) || plugin.proposals() == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Proposals.Proposal proposal : plugin.proposals()
                .pendingFor(player.getUniqueId(), System.currentTimeMillis())) {
            names.add(proposal.proposerName());
        }
        return names;
    }

    private List<String> knownNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            names.add(online.getName());
        }
        if (plugin.store() != null) {
            for (MarriageRecord record : plugin.store().all()) {
                if (!record.firstName().isBlank()) {
                    names.add(record.firstName());
                }
                if (!record.secondName().isBlank()) {
                    names.add(record.secondName());
                }
            }
        }
        return List.copyOf(names);
    }

    private List<String> filter(Iterable<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option != null && option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
