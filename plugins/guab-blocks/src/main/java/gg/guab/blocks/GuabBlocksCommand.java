package gg.guab.blocks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class GuabBlocksCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("add", "remove", "list", "reload");
    private static final List<String> TYPES = List.of("fake", "potion", "swap");

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
            case "remove" -> remove(sender);
            case "list" -> list(sender);
            case "reload" -> reload(sender);
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
        plugin.blocks().add(block, type);
        Messages.info(sender, (existed ? "Updated " : "Added ") + type.id() + " block at "
                + block.getWorld().getName() + " " + block.getX() + ", " + block.getY() + ", " + block.getZ()
                + " (" + plugin.blocks().size() + " total).");
    }

    private void remove(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Messages.error(sender, "Run this in game so I can see the block you are standing on.");
            return;
        }
        Block ground = player.getLocation().getBlock().getRelative(BlockFace.DOWN);
        Block block = plugin.blocks().contains(ground) ? ground : player.getTargetBlockExact(8);
        if (block == null || !plugin.blocks().contains(block)) {
            Messages.error(sender, "Stand on or look at a tagged block within 8 blocks.");
            return;
        }
        PrankBlock entry = plugin.blocks().get(block);
        plugin.blocks().remove(block);
        Messages.info(sender, "Removed " + entry.type().id() + " block at "
                + block.getWorld().getName() + " " + block.getX() + ", " + block.getY() + ", " + block.getZ()
                + " (" + plugin.blocks().size() + " left).");
    }

    private void list(CommandSender sender) {
        var blocks = plugin.blocks().all();
        if (blocks.isEmpty()) {
            Messages.info(sender, "No prank blocks are tagged.");
            return;
        }
        Messages.info(sender, "Prank blocks (" + blocks.size() + "):");
        for (PrankBlock block : blocks) {
            sender.sendMessage(Component.text("  " + block.world() + " " + block.x() + ", " + block.y() + ", "
                            + block.z() + " ", NamedTextColor.GRAY)
                    .append(Component.text(block.type().id(), NamedTextColor.GOLD))
                    .append(Component.text(details(block), NamedTextColor.DARK_GRAY)));
        }
    }

    private void reload(CommandSender sender) {
        plugin.pranks().restoreAll();
        plugin.reload();
        Messages.info(sender, "Reloaded config.yml and blocks.yml (" + plugin.blocks().size()
                + " blocks, " + plugin.config().potionEffects().size() + " potion effects).");
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

    private Block targetBlock(Player player) {
        Block ground = player.getLocation().getBlock().getRelative(BlockFace.DOWN);
        if (!ground.getType().isAir() && !ground.isLiquid()) {
            return ground;
        }
        Block target = player.getTargetBlockExact(8);
        return target != null && !target.getType().isAir() ? target : null;
    }

    private void usage(CommandSender sender) {
        Messages.info(sender, "/guabblocks add <fake|potion|swap> - tag the block under your feet "
                + "(or the block you are looking at up to 8 blocks away)");
        Messages.info(sender, "/guabblocks remove - untag the block under your feet or the one you are looking at");
        Messages.info(sender, "/guabblocks list - list tagged blocks");
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
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            return filter(TYPES, args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }
}
