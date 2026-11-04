package gg.guab.rickroll;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;

public final class RickrollCommand implements CommandExecutor, TabCompleter {

    private final GuabRickrollPlugin plugin;

    public RickrollCommand(GuabRickrollPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "setblock" -> setBlock(sender);
            case "remove" -> remove(sender);
            case "test" -> test(sender, args);
            case "stop" -> stop(sender, args);
            case "verify" -> verify(sender);
            case "reload" -> reload(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void setBlock(CommandSender sender) {
        if (!admin(sender)) {
            error(sender, "You need guab.rickroll.admin to mark rickroll blocks.");
            return;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Run this in game so I can see the block you are looking at.");
            return;
        }
        var block = player.getTargetBlockExact(8);
        if (block == null || block.getType() == Material.AIR) {
            error(sender, "Look at a block within 8 blocks.");
            return;
        }
        plugin.blocks().add(block);
        info(sender, "Rickroll block added at " + block.getX() + ", " + block.getY() + ", " + block.getZ()
                + " (" + plugin.blocks().size() + " total).");
    }

    private void remove(CommandSender sender) {
        if (!admin(sender)) {
            error(sender, "You need guab.rickroll.admin to remove rickroll blocks.");
            return;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Run this in game.");
            return;
        }
        var block = player.getTargetBlockExact(8);
        if (block == null) {
            error(sender, "Look at a block within 8 blocks.");
            return;
        }
        plugin.blocks().remove(block);
        info(sender, "Rickroll block removed (" + plugin.blocks().size() + " left).");
    }

    private void test(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!admin(sender)) {
                error(sender, "Only staff can rickroll someone else.");
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                error(sender, "Player not online: " + args[1]);
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            error(sender, "Usage: /rickroll test <player>");
            return;
        }
        if (plugin.shows().isActive(target)) {
            error(sender, target.getName() + " is already in a show.");
            return;
        }
        plugin.shows().start(target);
        info(sender, "Starting rickroll for " + target.getName() + ".");
    }

    private void stop(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            error(sender, "Only staff can stop a show.");
            return;
        }
        if (args.length >= 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                error(sender, "Player not online: " + args[1]);
                return;
            }
            plugin.shows().stop(target);
            info(sender, "Stopped the show for " + target.getName() + ".");
            return;
        }
        plugin.shows().stopAll();
        info(sender, "Stopped every active show.");
    }

    private void verify(CommandSender sender) {
        if (!admin(sender)) {
            error(sender, "Only staff can run verification.");
            return;
        }
        RickrollConfig config = plugin.config();
        info(sender, "frames=" + config.frameCount() + " fps=" + config.fps()
                + " duration=" + config.showSeconds() + "s chance=" + config.chance());

        File file = new File(config.packFile());
        if (!file.isFile()) {
            error(sender, "Pack missing: " + file.getAbsolutePath());
            return;
        }
        info(sender, "pack=" + file.length() + " bytes sha1=" + config.sha1());
        try (ZipFile zip = new ZipFile(file)) {
            info(sender, "mcmeta=" + (zip.getEntry("pack.mcmeta") != null)
                    + " model=" + (zip.getEntry("assets/guab/models/item/frame/0001.json") != null)
                    + " definition=" + (zip.getEntry("assets/guab/items/frame/0001.json") != null)
                    + " texture=" + (zip.getEntry("assets/guab/textures/frame/0001.png") != null)
                    + " sound=" + (zip.getEntry("assets/guab/sounds/rickroll.ogg") != null));
        } catch (IOException exception) {
            error(sender, "Could not read pack: " + exception.getMessage());
        }
        info(sender, "sha1 match=" + sha1(file).equalsIgnoreCase(config.sha1()));

        List<ItemStack> frames = plugin.cinema().frames();
        ItemDisplay display = plugin.cinema().spawnScreen();
        info(sender, "Cinema screen spawned, cycling " + Math.min(20, frames.size()) + " frames...");
        new BukkitRunnable() {
            private int index;

            @Override
            public void run() {
                if (index >= 20) {
                    display.remove();
                    info(sender, "Screen test complete.");
                    cancel();
                    return;
                }
                display.setItemStack(frames.get(index % frames.size()));
                index++;
            }
        }.runTaskTimer(plugin, 1L, 2L);
    }

    private void reload(CommandSender sender) {
        if (!admin(sender)) {
            error(sender, "Only staff can reload.");
            return;
        }
        plugin.reload();
        info(sender, "Reloaded (frames=" + plugin.config().frameCount() + ", fps=" + plugin.config().fps() + ").");
    }

    private boolean admin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        return player.hasPermission("guab.rickroll.admin");
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text("/rickroll setblock|remove|test [player]|stop [player]|verify|reload", NamedTextColor.GRAY));
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Rickroll] ", NamedTextColor.LIGHT_PURPLE).append(Component.text(message, NamedTextColor.GRAY)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Rickroll] ", NamedTextColor.LIGHT_PURPLE).append(Component.text(message, NamedTextColor.RED)));
    }

    private String sha1(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            return "error";
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("setblock", "remove", "test", "stop", "verify", "reload").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("test") || args[0].equalsIgnoreCase("stop"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
