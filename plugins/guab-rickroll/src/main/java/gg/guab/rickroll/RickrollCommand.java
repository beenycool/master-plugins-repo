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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class RickrollCommand implements CommandExecutor, TabCompleter {

    private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TEXTURE = Pattern.compile("\"layer0\"\\s*:\\s*\"([^\"]+)\"");

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
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "setblock" -> setBlock(sender);
            case "remove" -> remove(sender);
            case "test" -> test(sender, args);
            case "stop" -> stop(sender, args);
            case "stopall" -> stopAll(sender);
            case "pause" -> pause(sender, true);
            case "resume" -> pause(sender, false);
            case "status" -> status(sender, args);
            case "pack" -> pack(sender, args);
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
        if (plugin.blocks().remove(block)) {
            info(sender, "Rickroll block removed (" + plugin.blocks().size() + " left).");
        } else {
            error(sender, "That block is not a rickroll block.");
        }
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
        ShowManager.RequestResult result = plugin.shows().request(target);
        switch (result) {
            case STARTED -> info(sender, "Starting rickroll for " + target.getName() + ".");
            case QUEUED -> info(sender, "The cinema is busy with " + activeSummary()
                    + "; queued " + target.getName() + " (#" + plugin.shows().queueSize() + ").");
            case ALREADY_ACTIVE -> error(sender, target.getName() + " is already in a show.");
        }
    }

    private void stop(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
            stopAll(sender);
            return;
        }
        if (args.length >= 2) {
            if (!admin(sender)) {
                error(sender, "Only staff can stop someone else's show.");
                return;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                error(sender, "Player not online: " + args[1]);
                return;
            }
            if (!plugin.shows().isActive(target) && !plugin.shows().isQueued(target)) {
                error(sender, target.getName() + " is not in a show or queue.");
                return;
            }
            plugin.shows().stop(target);
            info(sender, "Stopped the show for " + target.getName() + ".");
            return;
        }
        if (sender instanceof Player player) {
            // Self-service: a victim can always stop their own show without staff.
            if (!plugin.shows().isActive(player) && !plugin.shows().isQueued(player)) {
                info(sender, "You are not in a rickroll show.");
                return;
            }
            plugin.shows().stop(player);
            info(sender, "Stopped your rickroll.");
            return;
        }
        stopAll(sender);
    }

    private void stopAll(CommandSender sender) {
        if (!admin(sender)) {
            error(sender, "Only staff can stop every show.");
            return;
        }
        int active = plugin.shows().activeCount();
        int queued = plugin.shows().queueSize();
        plugin.shows().stopAll();
        info(sender, "Stopped " + active + " active show(s) and cleared " + queued + " queued request(s).");
    }

    private void pause(CommandSender sender, boolean paused) {
        if (!admin(sender)) {
            error(sender, "Only staff can pause or resume rickroll triggers.");
            return;
        }
        plugin.triggers().setPaused(paused);
        info(sender, paused
                ? "Natural rickroll triggers paused. /rickroll test still works."
                : "Natural rickroll triggers resumed.");
    }

    private void status(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!admin(sender)) {
                error(sender, "Only staff can inspect another player.");
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
            target = null;
        }

        RickrollConfig config = plugin.config();
        info(sender, "chance=" + percent(config.chance())
                + (plugin.triggers().paused() ? " (paused)" : "")
                + " cooldown=" + (config.cooldownMillis() / 1000L) + "s"
                + " show=" + config.showSeconds() + "s"
                + " fps=" + config.fps() + " frames=" + config.frameCount());

        String active = plugin.shows().activeName();
        if (active == null) {
            info(sender, "active=none queued=" + plugin.shows().queueSize());
        } else {
            List<String> queued = plugin.shows().queuedNames();
            info(sender, "active=" + active + " (" + plugin.shows().activeElapsedSeconds() + "s)"
                    + " queued=" + plugin.shows().queueSize()
                    + (queued.isEmpty() ? "" : " " + queued));
        }

        info(sender, "pack=" + (config.packUsable() ? "enabled" : "disabled/blank")
                + " id=" + (plugin.packs().packId() == null ? "none" : plugin.packs().packId())
                + " hash=" + (config.sha1() == null || config.sha1().isBlank() ? "none" : config.sha1()));
        info(sender, "blocks=" + plugin.blocks().size()
                + " worlds=" + config.ignoreWorlds()
                + " gamemodes=" + config.ignoreGameModes()
                + " join-grace=" + config.joinGraceSeconds() + "s"
                + " teleport-grace=" + config.teleportGraceSeconds() + "s");
        if (target != null) {
            long remaining = plugin.triggers().cooldownRemainingMillis(target);
            info(sender, target.getName() + " pack=" + plugin.packs().statusText(target)
                    + " cooldown=" + String.format(Locale.ROOT, "%.1fs", remaining / 1000.0)
                    + " optout=" + plugin.rickrollOptOut(target)
                    + " reduced-motion=" + plugin.rickrollReducedMotion(target)
                    + (plugin.shows().isQueued(target) ? " queued=yes" : ""));
        }
    }

    private void pack(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!admin(sender)) {
                error(sender, "Only staff can send the pack to someone else.");
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
            error(sender, "Usage: /rickroll pack <player>");
            return;
        }
        if (!plugin.packs().available()) {
            error(sender, "The rickroll pack is disabled or pack.url is blank; shows fall back immediately.");
            return;
        }
        if (plugin.packs().resend(target)) {
            info(sender, "Re-requested the rickroll pack for " + target.getName()
                    + " (id=" + plugin.packs().packId() + ").");
        } else {
            error(sender, "Could not send the pack to " + target.getName() + "; check the server log.");
        }
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
                    + " sound=" + (zip.getEntry("assets/guab/sounds/rickroll.ogg") != null)
                    + " sounds.json=" + soundEventPresent(zip));
            verifyFrames(sender, zip, config);
            verifyDuration(sender, config);
        } catch (IOException exception) {
            error(sender, "Could not read pack: " + exception.getMessage());
            return;
        }
        String actual = sha1(file);
        if (actual.equalsIgnoreCase(config.sha1())) {
            info(sender, "sha1 match=true");
        } else {
            error(sender, "sha1 mismatch: pack=" + actual + " pack.properties=" + config.sha1());
        }

        List<ItemStack> frames = plugin.cinema().frames();
        if (frames.isEmpty()) {
            info(sender, "No frames to cycle; screen test skipped.");
            return;
        }
        ItemDisplay display;
        try {
            display = plugin.cinema().spawnScreen();
        } catch (Throwable throwable) {
            error(sender, "Could not spawn the test screen: " + throwable.getClass().getSimpleName());
            return;
        }
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

    private void verifyFrames(CommandSender sender, ZipFile zip, RickrollConfig config) throws IOException {
        List<Integer> missing = new ArrayList<>();
        for (int index = 1; index <= config.frameCount(); index++) {
            String definitionName = String.format(Locale.ROOT, "assets/guab/items/frame/%04d.json", index);
            String definition = readEntry(zip, definitionName);
            if (definition == null) {
                missing.add(index);
                continue;
            }
            String modelId = firstMatch(MODEL, definition);
            if (modelId == null) {
                missing.add(index);
                continue;
            }
            String modelName = assetPath(modelId, "models/item", ".json");
            String model = readEntry(zip, modelName);
            if (model == null) {
                missing.add(index);
                continue;
            }
            String textureId = firstMatch(TEXTURE, model);
            if (textureId == null) {
                missing.add(index);
                continue;
            }
            String textureName = assetPath(textureId, "textures/item", ".png");
            if (zip.getEntry(textureName) == null) {
                missing.add(index);
            }
        }
        if (missing.isEmpty()) {
            info(sender, "all " + config.frameCount() + " frames resolve to a model and texture.");
        } else {
            String preview = missing.size() <= 8
                    ? missing.toString()
                    : missing.subList(0, 8) + "...";
            error(sender, "frame check failed: " + missing.size() + "/" + config.frameCount()
                    + " broken " + preview);
        }
    }

    private void verifyDuration(CommandSender sender, RickrollConfig config) {
        long expected = Math.round(config.showSeconds() * (double) config.fps());
        long tolerance = Math.max(2L, Math.round(expected * 0.02));
        long diff = Math.abs(expected - config.frameCount());
        if (diff <= tolerance) {
            info(sender, "duration check: " + config.showSeconds() + "s*" + config.fps()
                    + "fps=" + expected + " frames, pack has " + config.frameCount() + " (ok).");
        } else {
            error(sender, "duration check failed: " + config.showSeconds() + "s*" + config.fps()
                    + "fps=" + expected + " frames but pack has " + config.frameCount()
                    + " (tolerance " + tolerance + "). The video will run out early or clip.");
        }
    }

    private boolean soundEventPresent(ZipFile zip) throws IOException {
        String sounds = readEntry(zip, "assets/guab/sounds.json");
        return sounds != null && sounds.contains("\"rickroll\"");
    }

    private String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String firstMatch(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String assetPath(String id, String folder, String extension) {
        String namespace = "guab";
        String path = id;
        int colon = id.indexOf(':');
        if (colon >= 0) {
            namespace = id.substring(0, colon);
            path = id.substring(colon + 1);
        }
        return "assets/" + namespace + "/" + folder + "/" + path + extension;
    }

    private void reload(CommandSender sender) {
        if (!admin(sender)) {
            error(sender, "Only staff can reload.");
            return;
        }
        plugin.reload();
        info(sender, "Reloaded config, lyrics and blocks (frames=" + plugin.config().frameCount()
                + ", fps=" + plugin.config().fps() + ", blocks=" + plugin.blocks().size() + ").");
    }

    private boolean admin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        return player.hasPermission("guab.rickroll.admin");
    }

    private String activeSummary() {
        String active = plugin.shows().activeName();
        if (active == null) {
            return "another show";
        }
        return active + " (" + plugin.shows().activeElapsedSeconds() + "s)";
    }

    private String percent(double chance) {
        return String.format(Locale.ROOT, "%.1f%%", chance * 100.0);
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "/rickroll setblock|remove|test [player]|stop [player|all]|stopall|"
                        + "pause|resume|status [player]|pack [player]|verify|reload",
                NamedTextColor.GRAY));
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Rickroll] ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Rickroll] ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(message, NamedTextColor.RED)));
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
            return List.of("setblock", "remove", "test", "stop", "stopall", "pause", "resume",
                            "status", "pack", "verify", "reload").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("test") || sub.equals("stop") || sub.equals("status") || sub.equals("pack")) {
                List<String> names = new ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                if (sub.equals("stop")) {
                    names.add("all");
                }
                return names.stream()
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }
        }
        return List.of();
    }
}
