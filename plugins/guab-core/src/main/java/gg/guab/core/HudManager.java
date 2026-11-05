package gg.guab.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
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
 */
public final class HudManager implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final GuabCorePlugin plugin;
    private final PointsService points;
    private final Map<UUID, Panel> panels = new HashMap<>();
    private BukkitTask task;

    public HudManager(GuabCorePlugin plugin, PointsService points) {
        this.plugin = plugin;
        this.points = points;
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
            if (panels.containsKey(player.getUniqueId())) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        panels.clear();
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
        if (!player.isOnline()) {
            return;
        }
        Panel panel = new Panel(player);
        panels.put(player.getUniqueId(), panel);
        panel.refresh();
    }

    private void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Panel panel = panels.get(player.getUniqueId());
            if (panel == null) {
                attach(player);
                continue;
            }
            panel.refresh();
        }
    }

    private Component parse(String raw) {
        return LEGACY.deserialize(raw == null ? "" : raw);
    }

    private String replace(String raw, Player player) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("%player%", player.getName())
                .replace("%points%", String.format(Locale.ROOT, "%,d", points.get(player.getUniqueId())))
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()));
    }

    private final class Panel {

        private final Player player;
        private final Scoreboard board;
        private final Objective objective;
        private final List<String> entries = new ArrayList<>();
        private final List<String> lastLines = new ArrayList<>();
        private Component lastHeader;
        private Component lastFooter;

        private Panel(Player player) {
            this.player = player;
            this.board = Bukkit.getScoreboardManager().getNewScoreboard();
            String title = plugin.getConfig().getString("hud.scoreboard.title", "&6&lGUAB");
            this.objective = board.registerNewObjective("guab", Criteria.DUMMY, parse(title));
            this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            player.setScoreboard(board);
        }

        private void refresh() {
            if (!player.isOnline()) {
                return;
            }
            if (plugin.getConfig().getBoolean("hud.scoreboard.enabled", true)) {
                refreshSidebar();
            }
            if (plugin.getConfig().getBoolean("hud.tab.enabled", true)) {
                refreshTab();
            }
        }

        private void refreshSidebar() {
            List<String> raw = plugin.getConfig().getStringList("hud.scoreboard.lines");
            if (raw.isEmpty()) {
                raw = List.of("&7Points &8» &6%points%", "", "&7Online &8» &f%online%");
            }
            List<String> lines = new ArrayList<>(raw.size());
            for (String template : raw) {
                lines.add(replace(template, player));
            }
            if (lines.equals(lastLines)) {
                return;
            }
            while (entries.size() < lines.size()) {
                int index = entries.size();
                String entry = "§" + Integer.toHexString(index % 16) + "§r";
                entries.add(entry);
                Team team = board.registerNewTeam("guab_line_" + index);
                team.addEntry(entry);
                objective.getScore(entry).setScore(lines.size() - index);
            }
            for (int i = 0; i < lines.size(); i++) {
                Team team = board.getTeam("guab_line_" + i);
                if (team != null) {
                    team.prefix(parse(lines.get(i)));
                }
                objective.getScore(entries.get(i)).setScore(lines.size() - i);
            }
            lastLines.clear();
            lastLines.addAll(lines);
        }

        private void refreshTab() {
            Component header = parse(replace(plugin.getConfig().getString("hud.tab.header", "&6&lGUAB PARKOUR"), player));
            Component footer = parse(replace(plugin.getConfig().getString("hud.tab.footer", "&7Points &8» &6%points%"), player));
            if (header.equals(lastHeader) && footer.equals(lastFooter)) {
                return;
            }
            player.sendPlayerListHeaderAndFooter(header, footer);
            lastHeader = header;
            lastFooter = footer;
        }
    }
}
