package gg.guab.marriage;

import gg.guab.core.GuabMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/** Shared chat formatting so every marriage line has the same prefix. */
final class MarriageMessages {

    static final String PLUGIN = "Marriage";
    static final NamedTextColor ACCENT = NamedTextColor.GOLD;

    private MarriageMessages() {
    }

    static Component info(String message) {
        return GuabMessages.info(PLUGIN, message);
    }

    static Component error(String message) {
        return GuabMessages.error(PLUGIN, message);
    }

    static void info(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(info(message));
        }
    }

    static void error(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(error(message));
        }
    }

    static Component accent(String message) {
        return Component.text(message, ACCENT);
    }
}
