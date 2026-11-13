package gg.guab.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player sidebar and tab-list header: the balance, the online count and whatever
 * the event needs players to remember. Refreshed on a timer so point changes show up
 * even when they come from a third-party plugin through the Vault bridge.
 *
 * <p>Visibility is driven by {@link PlayerPreferencesStore}; the manager keeps no
 * separate in-memory hidden set, so toggles survive relogs.</p>
 */
public final class HudManager implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final GuabCorePlugin plugin;
    private final PointsService points;
    private final StatsService stats;
    private final PlayerPreferencesStore preferences;
    private final Map<UUID, Panel> panels = new HashMap<>();
    private BukkitTask task;

    public HudManager(GuabCorePlugin plugin, PointsService points, StatsService stats,
                      PlayerPreferencesStore preferences) {
        this.plugin = plugin;
        this.points = points;
        this.stats = stats;
        this.preferences = preferences;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            attach(player);
        }
        int ticks = Math.max(5, plugin.getConfig().getInt("hud.update-ticks", 20));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll, ticks, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!panels.containsKey(player.getUniqueId())) {
                continue;
            }
            try {
                player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Could not clear HUD for " + player.getName()
                        + ": " + exception.getMessage());
            }
        }
        panels.clear();
    }

    public void applyPreferences(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Panel panel = panels.get(player.getUniqueId());
        if (panel == null) {
            attach(player);
            return;
        }
        try {
            panel.refresh();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not refresh HUD for " + player.getName()
                    + ": " + exception.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // One tick later so the player is fully in the world before the sidebar lands.
        plugin.getServer().getScheduler().runTask(plugin, () -> attach(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        panels.remove(event.getPlayer().getUniqueId());
    }

    private void attach(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            Panel panel = new Panel(player);
            panels.put(player.getUniqueId(), panel);
            panel.refresh();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not attach HUD for " + player.getName()
                    + ": " + exception.getMessage());
        }
    }

    private void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                Panel panel = panels.get(player.getUniqueId());
                if (panel == null) {
                    attach(player);
                    continue;
                }
                panel.refresh();
            } catch (RuntimeException exception) {
                // One bad provider or scoreboard must not cancel the repeating task.
                plugin.getLogger().warning("HUD update failed for " + player.getName()
                        + ": " + exception.getMessage());
            }
        }
    }

    private Component parse(String raw) {
        return LEGACY.deserialize(raw == null ? "" : raw);
    }

    private static boolean isHudGlyph(char value) {
        return value >= GuabGlyphs.COIN && value <= GuabGlyphs.LOGO;
    }

    private static boolean hasHudGlyph(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isHudGlyph(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String stripHudGlyphs(String text) {
        StringBuilder plain = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (!isHudGlyph(text.charAt(i))) {
                plain.append(text.charAt(i));
            }
        }
        return plain.toString();
    }

    /**
     * Parses a line and gives any pack glyph its own {@code guab:hud} component.
     * The local {@link #withIcon(Player, char, NamedTextColor, Component)} handles
     * the coin that the manager adds itself; this path covers glyphs written
     * directly into config lines (beer, dart, skull, logo).
     *
     * <p>When the pack is disabled globally or declined by this player, every
     * private-use glyph is stripped instead of being rendered as a tofu box.</p>
     */
    private Component parseIcons(String raw, boolean declined) {
        String text = raw == null ? "" : raw;
        if (!hasHudGlyph(text)) {
            return parse(text);
        }
        if (!fontIcons() || declined) {
            return parse(stripHudGlyphs(text));
        }
        Component out = Component.empty();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (!isHudGlyph(value)) {
                continue;
            }
            if (i > start) {
                out = out.append(parse(text.substring(start, i)));
            }
            out = out.append(GuabGlyphs.icon(value, null));
            start = i + 1;
        }
        if (start < text.length()) {
            out = out.append(parse(text.substring(start)));
        }
        return out;
    }

    /**
     * Whether the configured title/header already opens with the logo glyph,
     * skipping legacy formatting codes.
     */
    private static boolean startsWithGlyph(String text, char glyph) {
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '&' || value == '\u00A7') {
                i++;
                continue;
            }
            return value == glyph;
        }
        return false;
    }

    /**
     * Prefixes the sidebar title or tab header with the small logo unless the
     * configured text already starts with it. Respects the optional
     * {@code hud.logo} switch, the global icon switch and the player's
     * resource-pack decision.
     */
    private Component withLogo(String raw, boolean declined) {
        String text = raw == null ? "" : raw;
        Component body = parseIcons(text, declined);
        if (!logoEnabled() || !fontIcons() || declined || startsWithGlyph(text, GuabGlyphs.LOGO)) {
            return body;
        }
        return GuabGlyphs.withIcon(fontIcons(), declined,
                GuabGlyphs.LOGO, NamedTextColor.GOLD, body);
    }

    /** Optional, separate switch for the sidebar/tab logo; defaults to on. */
    private boolean logoEnabled() {
        return plugin.getConfig().getBoolean("hud.logo", true);
    }

    /**
     * Whether private-use glyphs from the Guab resource pack should be drawn.
     * Admins can turn this off when the pack is not installed, because clients
     * without the font render the codepoints as missing-character boxes.
     */
    private boolean fontIcons() {
        return plugin.getConfig().getBoolean("hud.font-icons", true);
    }

    /**
     * Prefixes a component with a bitmap glyph. The glyph carries the custom
     * font; the wrapper deliberately does not, so the rest of the line keeps
     * the default font instead of inheriting a font with no ASCII glyphs.
     */
    private Component withIcon(Player player, char glyph, NamedTextColor colour, Component body) {
        return GuabGlyphs.withIcon(fontIcons(), player, glyph, colour, body);
    }

    private HudTokens tokens(Player player) {
        HudTokens tokens = new HudTokens()
                .put("%player%", player.getName())
                .put("%points%", formatNumber(points.get(player.getUniqueId())))
                .put("%deaths%", String.valueOf(stats.get(player.getUniqueId(), StatsService.DEATHS)))
                .put("%voiddeaths%",
                        String.valueOf(stats.get(player.getUniqueId(), StatsService.VOID_DEATHS)))
                .put("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .put("%area%", areaName(player));
        List<Map.Entry<UUID, Long>> ranking = points.top(3);
        for (int i = 0; i < 3; i++) {
            tokens.put("%top" + (i + 1) + "%", topLine(ranking, i));
        }
        return tokens;
    }

    private static String formatNumber(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private String areaName(Player player) {
        for (HudContextService provider : contextProviders()) {
            try {
                String area = provider.area(player);
                if (area != null && !area.isBlank()) {
                    return area;
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("HUD area provider "
                        + provider.getClass().getName() + " failed: " + exception.getMessage());
            }
        }
        String world = player.getWorld().getName();
        return switch (world) {
            case "guab_casino" -> "Casino";
            case "guab_darts" -> "Darts";
            case "guab_cinema" -> "Cinema";
            default -> plugin.getConfig().getString("hud.area.default", "Guab");
        };
    }

    private List<HudContextService> contextProviders() {
        var registrations = plugin.getServer().getServicesManager()
                .getRegistrations(HudContextService.class);
        List<HudContextService> providers = new ArrayList<>(registrations.size());
        Map<HudContextService, Integer> priorities = new HashMap<>();
        for (var registration : registrations) {
            try {
                HudContextService provider = registration.getProvider();
                providers.add(provider);
                try {
                    priorities.put(provider, provider.priority());
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("HUD provider "
                            + provider.getClass().getName() + " priority failed: "
                            + exception.getMessage());
                    priorities.put(provider, Integer.MIN_VALUE);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Could not read a HUD service registration: "
                        + exception.getMessage());
            }
        }
        providers.sort(java.util.Comparator
                .comparingInt((HudContextService provider) ->
                        priorities.getOrDefault(provider, 0))
                .reversed());
        return providers;
    }

    private List<String> safeLines(HudContextService provider, Player player) {
        try {
            List<String> lines = provider.lines(player);
            return lines == null ? List.of() : lines;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("HUD context provider "
                    + provider.getClass().getName() + " failed: " + exception.getMessage());
            return List.of();
        }
    }

    private String topLine(List<Map.Entry<UUID, Long>> ranking, int index) {
        if (index >= ranking.size()) {
            return "";
        }
        Map.Entry<UUID, Long> entry = ranking.get(index);
        String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
        if (name == null) {
            name = entry.getKey().toString().substring(0, 8);
        }
        return name + " " + formatNumber(entry.getValue());
    }

    private final class Panel {

        private final Player player;
        private final Scoreboard board;
        private final Objective objective;
        private final Objective belowName;
        private final List<String> entries = new ArrayList<>();
        private final List<String> lastLines = new ArrayList<>();
        private final Map<String, Integer> lastScores = new HashMap<>();
        private boolean lastFontIcons;
        private boolean lastDeclined;
        private int lastPointsLine = -1;
        private Component lastHeader;
        private Component lastFooter;
        private String lastTitle;
        private String lastBelowNameTitle;
        private boolean lastTitleLogo;
        private boolean lastBelowNameIcon;

        private Panel(Player player) {
            this.player = player;
            this.board = Bukkit.getScoreboardManager().getNewScoreboard();
            String title = plugin.getConfig().getString("hud.scoreboard.title", "&6&lGUAB");
            boolean declined = GuabGlyphs.declined(player);
            this.objective = board.registerNewObjective("guab", Criteria.DUMMY,
                    withLogo(title, declined));
            this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            this.objective.numberFormat(NumberFormat.blank());
            this.lastTitle = title;
            this.lastTitleLogo = logoEnabled() && fontIcons() && !declined;
            this.belowName = registerBelowName();
            player.setScoreboard(board);
        }

        /**
         * The balance under the nametag uses the vanilla {@code below_name} slot, so every
         * player's own scoreboard decides what they see above other players' heads.
         */
        private Objective registerBelowName() {
            if (!plugin.getConfig().getBoolean("hud.below-name.enabled", true)) {
                return null;
            }
            String title = plugin.getConfig().getString("hud.below-name.title", "&6Points");
            boolean declined = GuabGlyphs.declined(player);
            Objective objective = board.registerNewObjective("guab_points", Criteria.DUMMY,
                    withIcon(player, GuabGlyphs.COIN, NamedTextColor.GOLD,
                            parseIcons(title, declined)));
            objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
            this.lastBelowNameTitle = title;
            this.lastBelowNameIcon = fontIcons() && !declined;
            return objective;
        }

        private void refresh() {
            if (!player.isOnline()) {
                return;
            }
            applyTitles();
            boolean spectatingOrCinema = player.getGameMode() == GameMode.SPECTATOR
                    || player.getWorld().getName().equals("guab_cinema");
            boolean showNametag = belowName != null
                    && preferences.nametag(player.getUniqueId())
                    && !spectatingOrCinema;
            if (belowName != null) {
                belowName.setDisplaySlot(showNametag ? DisplaySlot.BELOW_NAME : null);
            }

            boolean showSidebar = plugin.getConfig().getBoolean("hud.scoreboard.enabled", true)
                    && preferences.sidebar(player.getUniqueId())
                    && !spectatingOrCinema;
            boolean showTab = plugin.getConfig().getBoolean("hud.tab.enabled", true)
                    && preferences.tab(player.getUniqueId());
            if (showSidebar || showTab) {
                HudTokens tokens = tokens(player);
                if (showSidebar) {
                    refreshSidebar(tokens);
                } else {
                    board.clearSlot(DisplaySlot.SIDEBAR);
                }
                if (showTab) {
                    refreshTab(tokens);
                } else {
                    clearTab();
                }
            } else {
                board.clearSlot(DisplaySlot.SIDEBAR);
                clearTab();
            }
            if (showNametag) {
                refreshBelowName();
            }
        }

        /** Re-read the configurable titles so {@code /guabcore reload} applies edits. */
        private void applyTitles() {
            String title = plugin.getConfig().getString("hud.scoreboard.title", "&6&lGUAB");
            if (title == null) {
                title = "";
            }
            boolean declined = GuabGlyphs.declined(player);
            boolean titleLogo = logoEnabled() && fontIcons() && !declined;
            if (!title.equals(lastTitle) || titleLogo != lastTitleLogo) {
                lastTitle = title;
                lastTitleLogo = titleLogo;
                objective.displayName(withLogo(title, declined));
            }
            if (belowName == null) {
                return;
            }
            String below = plugin.getConfig().getString("hud.below-name.title", "&6Points");
            if (below == null) {
                below = "";
            }
            boolean belowIcon = fontIcons() && !declined;
            if (!below.equals(lastBelowNameTitle) || belowIcon != lastBelowNameIcon) {
                lastBelowNameTitle = below;
                lastBelowNameIcon = belowIcon;
                belowName.displayName(withIcon(player, GuabGlyphs.COIN,
                        NamedTextColor.GOLD, parseIcons(below, declined)));
            }
        }

        private void refreshBelowName() {
            if (belowName == null) {
                return;
            }
            // Drop entries for players who have left so the objective does not grow forever.
            for (String entry : new ArrayList<>(board.getEntries())) {
                if (!lastScores.containsKey(entry)) {
                    continue;
                }
                Player online = Bukkit.getPlayerExact(entry);
                if (online == null || !online.isOnline()) {
                    board.resetScores(entry);
                    lastScores.remove(entry);
                }
            }
            for (Player online : Bukkit.getOnlinePlayers()) {
                long value = points.get(online.getUniqueId());
                int display = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
                if (lastScores.getOrDefault(online.getName(), Integer.MIN_VALUE) == display) {
                    continue;
                }
                Score score = belowName.getScore(online.getName());
                score.setScore(display);
                lastScores.put(online.getName(), display);
            }
        }

        private void refreshSidebar(HudTokens tokens) {
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            List<String> raw = plugin.getConfig().getStringList("hud.scoreboard.lines");
            if (raw.isEmpty()) {
                raw = List.of("&7Points &8» &6%points%", "", "&7Online &8» &f%online%");
            }
            List<String> lines = new ArrayList<>(raw.size());
            List<Boolean> pointsFlags = new ArrayList<>(raw.size());
            boolean baseHasPoints = false;
            for (String template : raw) {
                lines.add(tokens.apply(template));
                boolean pointsLine = template.contains("%points%");
                pointsFlags.add(pointsLine);
                baseHasPoints |= pointsLine;
            }

            // Only the highest-priority provider that has anything to say owns the
            // context block. Layering every provider (pub + casino + pit) blew the
            // 15-line budget and made unrelated sections compete for the sidebar.
            List<String> context = new ArrayList<>();
            for (HudContextService provider : contextProviders()) {
                List<String> providerLines = new ArrayList<>();
                for (String line : safeLines(provider, player)) {
                    if (line == null || line.isEmpty()) {
                        continue;
                    }
                    // The base config already shows the balance; do not repeat it
                    // just because a venue context also carries a %points% line.
                    if (baseHasPoints && line.contains("%points%")) {
                        continue;
                    }
                    providerLines.add(tokens.apply(line));
                }
                if (!providerLines.isEmpty()) {
                    context.addAll(providerLines);
                    break;
                }
            }
            if (!context.isEmpty()) {
                int budget = Math.max(0, 15 - context.size() - (lines.isEmpty() ? 0 : 1));
                if (lines.size() > budget) {
                    lines = new ArrayList<>(lines.subList(0, budget));
                    pointsFlags = new ArrayList<>(pointsFlags.subList(0, budget));
                }
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
                    lines.add("");
                    pointsFlags.add(false);
                }
                lines.addAll(context);
                for (int i = 0; i < context.size(); i++) {
                    pointsFlags.add(false);
                }
            } else if (lines.size() > 15) {
                lines = new ArrayList<>(lines.subList(0, 15));
                pointsFlags = new ArrayList<>(pointsFlags.subList(0, 15));
            }

            // Recompute after truncation: the old index went stale and the coin icon
            // could land on the wrong line (or be lost entirely).
            int pointsLine = -1;
            for (int i = 0; i < pointsFlags.size(); i++) {
                if (pointsFlags.get(i)) {
                    pointsLine = i;
                    break;
                }
            }

            boolean icons = fontIcons();
            boolean declined = GuabGlyphs.declined(player);
            if (lines.equals(lastLines) && icons == lastFontIcons
                    && declined == lastDeclined && pointsLine == lastPointsLine) {
                return;
            }
            lastFontIcons = icons;
            lastDeclined = declined;
            lastPointsLine = pointsLine;
            if (entries.size() > lines.size()) {
                for (int i = lines.size(); i < entries.size(); i++) {
                    String entry = entries.get(i);
                    objective.getScore(entry).resetScore();
                    Team team = board.getTeam("guab_line_" + i);
                    if (team != null) {
                        team.removeEntry(entry);
                    }
                }
                entries.subList(lines.size(), entries.size()).clear();
            }
            while (entries.size() < lines.size()) {
                int index = entries.size();
                String entry = entryFor(index);
                entries.add(entry);
                Team team = board.getTeam("guab_line_" + index);
                if (team == null) {
                    team = board.registerNewTeam("guab_line_" + index);
                }
                team.addEntry(entry);
                objective.getScore(entry).setScore(lines.size() - index);
            }
            for (int i = 0; i < lines.size(); i++) {
                Team team = board.getTeam("guab_line_" + i);
                if (team != null) {
                    Component line = parseIcons(lines.get(i), declined);
                    if (i == pointsLine) {
                        line = withIcon(player, GuabGlyphs.COIN, NamedTextColor.GOLD, line);
                    }
                    team.prefix(line);
                }
                objective.getScore(entries.get(i)).setScore(lines.size() - i);
            }
            lastLines.clear();
            lastLines.addAll(lines);
        }

        /**
         * Builds an invisible, unique scoreboard entry for the given index. A single
         * colour-code pair only yields 16 unique values, so higher indices stack
         * additional pairs instead of colliding.
         */
        private static String entryFor(int index) {
            StringBuilder entry = new StringBuilder();
            int value = index;
            do {
                entry.append('§').append(Integer.toHexString(value & 0xF)).append('§').append('r');
                value >>>= 4;
            } while (value > 0);
            return entry.toString();
        }

        private void refreshTab(HudTokens tokens) {
            boolean declined = GuabGlyphs.declined(player);
            Component header = withLogo(tokens.apply(
                    plugin.getConfig().getString("hud.tab.header", "&6&lGUAB &8» &f%area%")),
                    declined);
            String footerRaw = plugin.getConfig().getString("hud.tab.footer",
                    "&7Points &8» &6%points%");
            Component footer = parseIcons(tokens.apply(footerRaw), declined);
            if (footerRaw != null && footerRaw.contains("%points%")) {
                footer = withIcon(player, GuabGlyphs.COIN, NamedTextColor.GOLD, footer);
            }
            if (header.equals(lastHeader) && footer.equals(lastFooter)) {
                return;
            }
            player.sendPlayerListHeaderAndFooter(header, footer);
            lastHeader = header;
            lastFooter = footer;
        }

        private void clearTab() {
            if (lastHeader == null && lastFooter == null) {
                return;
            }
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            lastHeader = null;
            lastFooter = null;
        }
    }
}
