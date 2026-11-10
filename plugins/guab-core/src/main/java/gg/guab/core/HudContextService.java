package gg.guab.core;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * Lets a feature plugin contribute a small, context-aware section to the shared
 * sidebar and tab header. Providers are only asked about the player they are
 * given, and should return an empty list outside their venue.
 */
public interface HudContextService {

    /** Extra sidebar lines in legacy {@code &} formatting, or an empty list. */
    List<String> lines(Player player);

    /** Optional tab-header area label, or {@code null} to use the core default. */
    default String area(Player player) {
        return null;
    }

    /** Higher priority providers are asked first. */
    default int priority() {
        return 0;
    }
}
