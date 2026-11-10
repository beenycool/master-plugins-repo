package gg.guab.blocks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class GuabBlocksCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "add", "remove", "list", "reload", "info", "test", "set", "tp");
    private static final List<String> TYPES = List.of("fake", "potion", "swap");
    private static final List<String> FIELDS = List.of(
            "chance", "delay", "cooldown", "effect", "duration", "amplifier", "sound");
    private static final int PAGE_SIZE = 8;

    private final GuabBlocksPlugin plugin;

    public GuabBlocksCommand(GuabBlocksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("guab.blocks.admin")) {
            Messages.error(sender, "You need guab.blocks.admin to manage prank blocks.");
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> add(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender, args);
            case "reload" -> reload(sender);
            case "info" -> info(sender, args);
            case "test" -> test(sender, args);
            case "set" -> set(sender, args);
            case "tp" -> teleport(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void add(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Messages.error(sender, "Usage: /guabblocks add <fake|potion|swap>");
            return;
        }
        PrankType type = PrankType.parse(args[1]);
        if (type == null) {
            Messages.error(sender, "Unknown block type '" + args[1] + "'. Use fake, potion or swap.");
            return;
        }
        if (!(sender instanceof Player player)) {
            Messages.error(sender, "Run this in game so I can see the block you are standing on.");
            return;
        }
        Block block = targetBlock(player);
        if (block == null) {
            Messages.error(sender, "Stand on a block or look at one within 8 blocks.");
            return;
        }
        boolean existed = plugin.blocks().contains(block);
        if (!plugin.blocks().add(block, type)) {
            Messages.error(sender, "Fake blocks must be simple solid blocks; containers, signs "
                    + "and other tile entities are not supported.");
            return;
        }
        Messages.info(sender, (existed ? "Updated " : "Added ") + type.id() + " block at "
                + block.getWorld().getName() + " " + block.getX() + ", " + block.getY() + ", " + block.getZ()
                + " (" + plugin.blocks().size() + " total).");
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length > 1) {
            Block block = blockFromArgs(sender, args, 1);
            if (block == null) {
                return;
            }
            boolean restored = plugin.pranks().restoreAt(block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ());
            PrankBlock removed = plugin.blocks().removeAt(block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ());
            if (!restored && removed == null) {
                Messages.error(sender, "No tagged or open fake block at "
                        + block.getWorld().getName() + " " + block.getX() + ", "
                        + block.getY() + ", " + block.getZ() + ".");
                return;
            }
            if (restored) {
                Messages.info(sender, "Restored the open fake block at "
                        + block.getWorld().getName() + " " + block.getX() + ", "
                        + block.getY() + ", " + block.getZ() + ".");
            }
            if (removed != null) {
                Messages.info(sender, "Removed " + removed.type().id() + " tag ("
                        + plugin.blocks().size() + " left).");
            }
            return;
        }

        if (!(sender instanceof Player player)) {
            Messages.error(sender, "From console use /guabblocks remove <world> <x> <y> <z>.");
            return;
        }
        Block ground = player.getLocation().getBlock().getRelative(BlockFace.DOWN);
        Block block = plugin.blocks().contains(ground) ? ground : player.getTargetBlockExact(8);
        if (block == null) {
            if (restorePending(player, ground)) {
                return;
            }
            Messages.error(sender, "Stand on or look at a tagged block within 8 blocks.");
            return;
        }
        PrankBlock entry = plugin.blocks().remove(block);
        if (entry == null) {
            if (restorePending(player, block)) {
                return;
            }
            Messages.error(sender, "Stand on or look at a tagged block within 8 blocks.");
            return;
        }
        Messages.info(sender, "Removed " + entry.type().id() + " block at "
                + block.getWorld().getName() + " " + block.getX() + ", " + block.getY() + ", " + block.getZ()
                + " (" + plugin.blocks().size() + " left).");
    }

    private boolean restorePending(Player player, Block block) {
        if (block == null) {
            return false;
        }
        if (!plugin.pranks().restoreAt(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ())) {
            return false;
        }
        Messages.info(player, "Restored the open fake block at "
                + block.getWorld().getName() + " " + block.getX() + ", "
                + block.getY() + ", " + block.getZ() + ".");
        return true;
    }

    private void info(CommandSender sender, String[] args) {
        Block block;
        if (args.length > 1) {
            block = blockFromArgs(sender, args, 1);
            if (block == null) {
                return;
            }
        } else {
            if (!(sender instanceof Player player)) {
                Messages.error(sender, "From console use /guabblocks info <world> <x> <y> <z>.");
                return;
            }
            block = resolveTarget(player);
            if (block == null) {
                Messages.error(sender, "Stand on or look at a block within 8 blocks.");
                return;
            }
        }

        PrankBlock entry = plugin.blocks().get(block);
        PrankManager.PendingRestoreInfo pending = plugin.pranks().pending(block);
        if (entry == null && pending == null) {
            Messages.info(sender, "No prank tag or pending restore at "
                    + block.getWorld().getName() + " " + block.getX() + ", "
                    + block.getY() + ", " + block.getZ() + ".");
            return;
        }
        if (entry != null) {
            Messages.info(sender, "Tag at " + block.getWorld().getName() + " " + block.getX()
                    + ", " + block.getY() + ", " + block.getZ() + ": " + entry.type().id()
                    + details(entry));
        } else {
            Messages.info(sender, "No tag on the block itself, but there is a pending restore here.");
        }
        if (pending != null) {
            Messages.info(sender, "Pending restore: " + pending.original() + " at "
                    + pending.world() + " " + pending.x() + ", " + pending.y() + ", " + pending.z() + ".");
        } else {
            Messages.info(sender, "Pending restore: none (the fake block is not currently open).");
        }
    }

    private void test(CommandSender sender, String[] args) {
        if (!(sender instanceof Player admin)) {
            Messages.error(sender, "Run this in game so I can see the block to test.");
            return;
        }
        Player target = admin;
        if (args.length > 1) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                Messages.error(sender, "Player not online: " + args[1]);
                return;
            }
        }
        Block block = targetBlock(admin);
        PrankBlock entry = block == null ? null : plugin.blocks().get(block);
        if (block == null || entry == null) {
            Messages.error(sender, "Stand on or look at a tagged block within 8 blocks.");
            return;
        }
        boolean applied = plugin.pranks().triggerForced(target, block, entry);
        if (applied) {
            Messages.info(sender, "Forced " + entry.type().id() + " prank on " + target.getName()
                    + " (chance and cooldowns bypassed).");
        } else {
            Messages.error(sender, "The " + entry.type().id()
                    + " prank could not run (no valid effect or safe swap target).");
        }
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Messages.error(sender, "Usage: /guabblocks set <chance|delay|cooldown|effect|duration|amplifier|sound> <value>");
            return;
        }
        if (!(sender instanceof Player player)) {
            Messages.error(sender, "Run this in game so I can see the tagged block.");
            return;
        }
        Block block = targetBlock(player);
        PrankBlock current = block == null ? null : plugin.blocks().get(block);
        if (block == null || current == null) {
            Messages.error(sender, "Stand on or look at a tagged block within 8 blocks.");
            return;
        }
        String field = args[1].toLowerCase(Locale.ROOT);
        PrankBlock updated = update(current, field, args[2], sender);
        if (updated == null) {
            return;
        }
        plugin.blocks().put(updated);
        Messages.info(sender, "Set " + field + " to '" + args[2] + "' at "
                + block.getWorld().getName() + " " + block.getX() + ", "
                + block.getY() + ", " + block.getZ() + ".");
    }

    private PrankBlock update(PrankBlock current, String field, String raw, CommandSender sender) {
        boolean clear = raw.equalsIgnoreCase("none") || raw.equalsIgnoreCase("reset");
        Double chance = current.chance();
        Integer restoreDelay = current.restoreDelayTicks();
        Integer cooldown = current.cooldownSeconds();
        String effect = current.effect();
        Integer duration = current.durationSeconds();
        Integer amplifier = current.amplifier();
        String sound = current.breakSound();

        switch (field) {
            case "chance" -> {
                if (clear) {
                    chance = null;
                } else {
                    try {
                        double value = Double.parseDouble(raw);
                        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                            Messages.error(sender, "Chance must be between 0.0 and 1.0.");
                            return null;
                        }
                        chance = value;
                    } catch (NumberFormatException exception) {
                        Messages.error(sender, "Chance must be a number between 0.0 and 1.0.");
                        return null;
                    }
                }
            }
            case "delay", "restore-delay", "restore-delay-ticks" -> {
                if (clear) {
                    restoreDelay = null;
                } else {
                    Integer value = parseInteger(sender, raw, 1, Integer.MAX_VALUE, "Delay");
                    if (value == null) {
                        return null;
                    }
                    restoreDelay = value;
                }
            }
            case "cooldown", "cooldown-seconds" -> {
                if (clear) {
                    cooldown = null;
                } else {
                    Integer value = parseInteger(sender, raw, 0, Integer.MAX_VALUE, "Cooldown");
                    if (value == null) {
                        return null;
                    }
                    cooldown = value;
                }
            }
            case "effect" -> {
                if (clear) {
                    effect = null;
                } else if (BlocksConfig.resolveEffect(raw, plugin.getLogger()) == null) {
                    Messages.error(sender, "Unknown potion effect '" + raw + "'.");
                    return null;
                } else {
                    effect = raw.trim();
                }
            }
            case "duration" -> {
                if (clear) {
                    duration = null;
                } else {
                    Integer value = parseInteger(sender, raw, 1, Integer.MAX_VALUE, "Duration");
                    if (value == null) {
                        return null;
                    }
                    duration = value;
                }
            }
            case "amplifier" -> {
                if (clear) {
                    amplifier = null;
                } else {
                    Integer value = parseInteger(sender, raw, 0, 255, "Amplifier");
                    if (value == null) {
                        return null;
                    }
                    amplifier = value;
                }
            }
            case "sound", "break-sound" -> {
                if (clear) {
                    sound = null;
                } else if (!BlocksConfig.isKnownSound(raw)) {
                    Messages.error(sender, "Unknown sound '" + raw + "'. Example: block.note_block.hat");
                    return null;
                } else {
                    sound = raw.trim();
                }
            }
            default -> {
                Messages.error(sender, "Unknown field. Use chance, delay, cooldown, effect, duration, amplifier or sound.");
                return null;
            }
        }
        return new PrankBlock(current.world(), current.x(), current.y(), current.z(), current.type(),
                chance, restoreDelay, cooldown, effect, duration, amplifier, sound);
    }

    private Integer parseInteger(CommandSender sender, String raw, int min, int max, String label) {
        try {
            int value = Integer.parseInt(raw);
            if (value < min || value > max) {
                Messages.error(sender, label + " must be between " + min + " and " + max + ".");
                return null;
            }
            return value;
        } catch (NumberFormatException exception) {
            Messages.error(sender, label + " must be a whole number.");
            return null;
        }
    }

    private void list(CommandSender sender, String[] args) {
        List<PrankBlock> blocks = new ArrayList<>(plugin.blocks().all());
        if (blocks.isEmpty()) {
            Messages.info(sender, "No prank blocks are tagged.");
            return;
        }
        blocks.sort(Comparator.comparing(PrankBlock::key));
        int pages = Math.max(1, (blocks.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = 1;
        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                Messages.error(sender, "Page must be a number.");
                return;
            }
        }
        page = Math.max(1, Math.min(page, pages));
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(blocks.size(), from + PAGE_SIZE);
        Messages.info(sender, "Prank blocks (" + blocks.size() + ") — page " + page + "/" + pages
                + ", " + plugin.pranks().pendingCount() + " pending restore(s):");
        for (int index = from; index < to; index++) {
            PrankBlock block = blocks.get(index);
            Component coordinates = Component.text(block.world() + " " + block.x() + ", "
                            + block.y() + ", " + block.z(), NamedTextColor.GRAY)
                    .clickEvent(ClickEvent.runCommand("/guabblocks tp " + block.world() + " "
                            + block.x() + " " + block.y() + " " + block.z()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to teleport")));
            sender.sendMessage(Component.text("  " + (index + 1) + ". ", NamedTextColor.DARK_GRAY)
                    .append(coordinates)
                    .append(Component.text(" " + block.type().id(), NamedTextColor.GOLD))
                    .append(Component.text(details(block), NamedTextColor.DARK_GRAY)));
        }
        if (pages > 1) {
            Messages.info(sender, "Next page: /guabblocks list " + (page < pages ? page + 1 : 1));
        }
    }

    private void teleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.error(sender, "Only players can teleport to a prank block.");
            return;
        }
        if (args.length < 2) {
            Messages.error(sender, "Usage: /guabblocks tp [world] <x> <y> <z>");
            return;
        }
        Block block = blockFromArgs(sender, args, 1);
        if (block == null) {
            return;
        }
        player.teleport(block.getLocation().add(0.5, 1.0, 0.5));
        player.setFallDistance(0.0f);
        Messages.info(sender, "Teleported to " + block.getWorld().getName() + " "
                + block.getX() + ", " + block.getY() + ", " + block.getZ() + ".");
    }

    private void reload(CommandSender sender) {
        plugin.pranks().restoreAll();
        boolean loaded = plugin.reload();
        if (loaded) {
            Messages.info(sender, "Reloaded config.yml and blocks.yml (" + plugin.blocks().size()
                    + " blocks, " + plugin.config().potionEffects().size() + " potion effects).");
        } else {
            Messages.error(sender, "config.yml reloaded, but blocks.yml was missing or malformed; "
                    + plugin.blocks().size() + " in-memory tag(s) were kept.");
        }
    }

    private String details(PrankBlock block) {
        StringBuilder out = new StringBuilder();
        if (block.chance() != null) {
            out.append(" chance=").append(block.chance());
        }
        if (block.restoreDelayTicks() != null) {
            out.append(" restore-delay-ticks=").append(block.restoreDelayTicks());
        }
        if (block.cooldownSeconds() != null) {
            out.append(" cooldown-seconds=").append(block.cooldownSeconds());
        }
        if (block.effect() != null) {
            out.append(" effect=").append(block.effect());
        }
        if (block.durationSeconds() != null) {
            out.append(" duration-seconds=").append(block.durationSeconds());
        }
        if (block.amplifier() != null) {
            out.append(" amplifier=").append(block.amplifier());
        }
        if (block.breakSound() != null) {
            out.append(" break-sound=").append(block.breakSound());
        }
        return out.toString();
    }

    private Block resolveTarget(Player player) {
        Block ground = player.getLocation().getBlock().getRelative(BlockFace.DOWN);
        if (plugin.blocks().contains(ground) || plugin.pranks().pending(ground) != null) {
            return ground;
        }
        Block target = player.getTargetBlockExact(8);
        if (target != null) {
            return target;
        }
        return ground;
    }

    private Block targetBlock(Player player) {
        Block ground = player.getLocation().getBlock().getRelative(BlockFace.DOWN);
        if (!ground.getType().isAir() && !ground.isLiquid()) {
            return ground;
        }
        Block target = player.getTargetBlockExact(8);
        return target != null && !target.getType().isAir() ? target : null;
    }

    private Block blockFromArgs(CommandSender sender, String[] args, int start) {
        int count = args.length - start;
        if (count == 3) {
            if (!(sender instanceof Player player)) {
                Messages.error(sender, "From console use the <world> <x> <y> <z> form.");
                return null;
            }
            return makeBlock(sender, player.getWorld(), args[start], args[start + 1], args[start + 2]);
        }
        if (count == 4) {
            World world = Bukkit.getWorld(args[start]);
            if (world == null) {
                Messages.error(sender, "Unknown world: " + args[start]);
                return null;
            }
            return makeBlock(sender, world, args[start + 1], args[start + 2], args[start + 3]);
        }
        Messages.error(sender, "Usage: [world] <x> <y> <z>");
        return null;
    }

    private Block makeBlock(CommandSender sender, World world, String x, String y, String z) {
        try {
            return new Location(world, Integer.parseInt(x), Integer.parseInt(y), Integer.parseInt(z)).getBlock();
        } catch (NumberFormatException exception) {
            Messages.error(sender, "Coordinates must be whole numbers.");
            return null;
        }
    }

    private void usage(CommandSender sender) {
        Messages.info(sender, "/guabblocks add <fake|potion|swap> - tag the block under your feet "
                + "(or the block you are looking at up to 8 blocks away)");
        Messages.info(sender, "/guabblocks remove [world] <x> <y> <z> - untag or restore an open fake block");
        Messages.info(sender, "/guabblocks info [world] <x> <y> <z> - show tag and pending restore details");
        Messages.info(sender, "/guabblocks test [player] - force the targeted prank, ignoring chance/cooldowns");
        Messages.info(sender, "/guabblocks set <chance|delay|cooldown|effect|duration|amplifier|sound> <value>");
        Messages.info(sender, "/guabblocks list [page] - list tagged blocks with clickable coordinates");
        Messages.info(sender, "/guabblocks tp [world] <x> <y> <z> - teleport to a tagged block");
        Messages.info(sender, "/guabblocks reload - reload config.yml and blocks.yml");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("guab.blocks.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "add" -> filter(TYPES, args[1]);
                case "set" -> filter(FIELDS, args[1]);
                case "test" -> filter(onlineNames(), args[1]);
                case "remove" -> filter(removeOrInfoOptions(), args[1]);
                case "info" -> filter(removeOrInfoOptions(), args[1]);
                case "tp" -> filter(worldNames(), args[1]);
                case "list" -> filter(List.of("1", "2", "3"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && sub.equals("set")) {
            String field = args[1].toLowerCase(Locale.ROOT);
            return filter(switch (field) {
                case "chance" -> List.of("0.5", "0.25", "0.75", "none");
                case "delay" -> List.of("40", "60", "100", "none");
                case "cooldown" -> List.of("0", "10", "30", "none");
                case "effect" -> List.of("nausea", "blindness", "slowness", "none");
                case "duration" -> List.of("5", "8", "10", "none");
                case "amplifier" -> List.of("0", "1", "2", "none");
                case "sound" -> List.of("block.note_block.hat", "block.glass.break", "none");
                default -> List.of();
            }, args[2]);
        }
        if (args.length == 3 && (sub.equals("remove") || sub.equals("info") || sub.equals("tp"))) {
            return filter(worldNames(), args[2]);
        }
        return List.of();
    }

    private List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private List<String> worldNames() {
        List<String> names = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            names.add(world.getName());
        }
        return names;
    }

    private List<String> removeOrInfoOptions() {
        return worldNames();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
