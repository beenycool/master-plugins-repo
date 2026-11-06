package gg.guab.blocks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

public final class Messages {

    private static final Component PREFIX = Component.text("[Blocks] ", NamedTextColor.GOLD);

    private Messages() {
    }

    public static void info(CommandSender sender, String message) {
        sender.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.GRAY)));
    }

    public static void error(CommandSender sender, String message) {
        sender.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.RED)));
    }
}
