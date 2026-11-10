package gg.guab.gambling;

import gg.guab.core.HudContextService;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Adds a compact casino section to the shared HUD while the player is in the
 * casino world. The balance token is expanded by the core HudManager.
 */
public final class CasinoHudContext implements HudContextService {

    private final GuabGamblingPlugin plugin;

    public CasinoHudContext(GuabGamblingPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String area(Player player) {
        if (player.getWorld().getName().equals(plugin.config().worldName())) {
            return "Casino";
        }
        return null;
    }

    @Override
    public List<String> lines(Player player) {
        if (!player.getWorld().getName().equals(plugin.config().worldName())) {
            return List.of();
        }
        boolean blackjackOpen = plugin.config().enabled()
                && plugin.getConfig().getBoolean("blackjack.enabled", true);
        return List.of(
                "&8Casino",
                "&7Blackjack &8» &f/bj &7for the table",
                "&7Status &8» " + (blackjackOpen ? "&aOpen" : "&cClosed"),
                "&7Balance &8» &6%points%");
    }
}
