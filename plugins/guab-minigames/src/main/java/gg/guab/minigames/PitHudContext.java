package gg.guab.minigames;

import gg.guab.core.HudContextService;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Contributes battle pit information to the shared sidebar and tab header. Kept
 * to three short lines so the shared HUD stays readable next to other plugins.
 */
final class PitHudContext implements HudContextService {

    private final GuabMinigamesPlugin plugin;

    PitHudContext(GuabMinigamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> lines(Player player) {
        if (!plugin.pit().inPit(player)) {
            return List.of();
        }
        BattlePit.SessionStats stats = plugin.pit().stats(player);
        int kills = stats == null ? 0 : stats.kills();
        int deaths = stats == null ? 0 : stats.deaths();
        int best = stats == null ? 0 : stats.bestStreak();
        return List.of(
                "&8Battle Pit",
                "&7K/D &8» &f" + kills + "&7/&f" + deaths
                        + " &8| &7Streak &8» &f" + plugin.pit().streak(player),
                "&7Best &8» &f" + best + " &8| &7Fighters &8» &f" + plugin.pit().fighterCount());
    }

    @Override
    public String area(Player player) {
        return plugin.pit().inPit(player) ? "Battle Pit" : null;
    }

    @Override
    public int priority() {
        return 30;
    }
}
