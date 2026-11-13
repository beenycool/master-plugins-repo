package gg.guab.items;

import gg.guab.core.GuabGlyphs;
import gg.guab.core.HudContextService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Contributes the pub and darts sections to the shared sidebar and tab header.
 */
public final class PubHudContext implements HudContextService {

    private static final String DARTS_WORLD = "guab_darts";

    private final GuabItemsPlugin plugin;

    public PubHudContext(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public String area(Player player) {
        String world = player.getWorld().getName();
        if (world.equals(DARTS_WORLD)) {
            return "Darts";
        }
        if (world.equals(casinoWorld())) {
            return "Casino";
        }
        return null;
    }

    @Override
    public List<String> lines(Player player) {
        String world = player.getWorld().getName();
        if (world.equals(DARTS_WORLD)) {
            DartsManager darts = plugin.darts();
            if (darts == null) {
                return List.of();
            }
            List<String> dartsLines = darts.hudLines(player);
            if (dartsLines.isEmpty()) {
                return dartsLines;
            }
            List<String> decorated = new ArrayList<>(dartsLines);
            String header = decorated.get(0);
            if (header != null && header.contains("Darts")) {
                decorated.set(0, "&8" + GuabGlyphs.DART + "&8Darts");
            }
            return decorated;
        }
        if (!world.equals(casinoWorld())) {
            return List.of();
        }
        PubManager pub = plugin.pub();
        if (pub == null) {
            return List.of();
        }
        Location centre = pub.centre();
        if (centre == null || !player.getWorld().equals(centre.getWorld())) {
            return List.of();
        }
        double radius = Math.max(4.0, plugin.getConfig().getDouble("beer.tap.radius", 12.0));
        if (player.getLocation().distanceSquared(centre) > radius * radius) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(5);
        lines.add("&8Pub");
        lines.add("&7Pints &8» &f" + pub.pints(player.getUniqueId()));
        lines.add("&7" + GuabGlyphs.BEER + "&7Drunk &8» &f"
                + String.format(Locale.ROOT, "%.1f", pub.drunkUnits(player.getUniqueId())) + "u");
        if (pub.happyHourActive()) {
            long minutes = Math.max(1L, (pub.happyHourRemainingMillis() + 59_999L) / 60_000L);
            lines.add("&6Happy Hour &8» &f" + minutes + "m");
        }
        lines.add("&7Beer &8» &f/beer");
        return lines;
    }

    private String casinoWorld() {
        return plugin.getConfig().getString("beer.tap.world", "guab_casino");
    }
}
