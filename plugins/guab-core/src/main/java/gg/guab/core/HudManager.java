package gg.guab.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player sidebar and tab-list header: the balance, the online count and whatever
 * the event needs players to remember. Refreshed on a timer so point changes show up
 * even when they come from a third-party plugin through the Vault bridge.
 */
public final class HudManager implements Listener, CommandExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final GuabCorePlugin plugin;
    private final PointsService points;
    private final Map<UUID, Panel> panels = new HashMap<>();
    private final Set<UUID> sidebarHidden = new HashSet<>();
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
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            attach(event.getPlayer());
            sendWelcome(event.getPlayer());
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        panels.remove(event.getPlayer().getUniqueId());
        sidebarHidden.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[HUD] Only players can toggle their own HUD.", NamedTextColor.RED));
            return true;
        }
        boolean visible = toggleSidebar(player);
        player.sendMessage(Component.text("[HUD] ", NamedTextColor.GOLD)
                .append(Component.text(visible
                        ? "Sidebar shown."
                        : "Sidebar hidden. Your balance stays under your nametag.", NamedTextColor.GRAY)));
        return true;
    }

    /** Returns whether the sidebar is now visible. */
    private boolean toggleSidebar(Player player) {
        UUID id = player.getUniqueId();
        boolean hide = !sidebarHidden.contains(id);
        if (hide) {
            sidebarHidden.add(id);
        } else {
            sidebarHidden.remove(id);
        }
        Panel panel = panels.get(id);
        if (panel != null) {
            panel.setSidebarVisible(!hide);
        }
        return !hide;
    }

    private static final List<String> DEFAULT_WELCOME = List.of(
            "&6&lWelcome to Guab, &f%player%&6!",
            "&7Balance &8» &6%points% &7points",
            "",
            "&6Where everything is:",
            "&eCasino &8» &f/casino &7— slots, roulette, chicken, fights",
            "&eWetherspoons &8» &7inside the casino, north-west corner &8— &f1-point beer",
            "&eBattle pit &8» &f/battlepit join &7— 50 points per kill",
            "&eBlackjack &8» &f/bj <bet> &7— hit, stand or double",
            "&ePoints &8» &f/points &7| &f/bal &7| &f/points top",
            "&eHUD &8» &f/hud &7to hide or show the sidebar");

    private void sendWelcome(Player player) {
        if (!player.isOnline() || !plugin.getConfig().getBoolean("hud.join-message.enabled", true)) {
            return;
        }
        List<String> template = plugin.getConfig().getStringList("hud.join-message.lines");
        if (template.isEmpty()) {
            template = DEFAULT_WELCOME;
        }
        player.sendMessage(Component.empty());
        for (String line : template) {
            player.sendMessage(parse(replace(line, player)));
        }
        player.sendMessage(Component.empty()
                .append(button("[Enter casino]", "/casino", NamedTextColor.GREEN, "Teleport to the casino"))
                .append(Component.text("  "))
                .append(button("[Battle pit]", "/battlepit join", NamedTextColor.RED, "Join the free-for-all pit"))
                .append(Component.text("  "))
                .append(button("[Blackjack]", "/bj 10", NamedTextColor.AQUA, "Start a 10-point blackjack hand")));
    }

    private Component button(String label, String command, NamedTextColor color, String hover) {
        return Component.text(label, color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    private void attach(Player player) {
        if (!player.isOnline()) {
            return;
        }
        Panel panel = new Panel(player);
        panels.put(player.getUniqueId(), panel);
        panel.refresh();
        panel.setSidebarVisible(!sidebarHidden.contains(player.getUniqueId()));
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
        String out = raw
                .replace("%player%", player.getName())
                .replace("%points%", String.format(Locale.ROOT, "%,d", points.get(player.getUniqueId())))
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()));
        if (out.contains("%top")) {
            List<Map.Entry<UUID, Integer>> ranking = points.top(3);
            for (int i = 0; i < 3; i++) {
                out = out.replace("%top" + (i + 1) + "%", topLine(ranking, i));
            }
        }
        return out;
    }

    private String topLine(List<Map.Entry<UUID, Integer>> ranking, int index) {
        if (index >= ranking.size()) {
            return "";
        }
        Map.Entry<UUID, Integer> entry = ranking.get(index);
        String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
        if (name == null) {
            name = entry.getKey().toString().substring(0, 8);
        }
        return name + " " + String.format(Locale.ROOT, "%,d", entry.getValue());
    }

    private final class Panel {

        private final Player player;
        private final Scoreboard board;
        private final Objective objective;
        private final Objective belowName;
        private final List<String> entries = new ArrayList<>();
        private final List<String> lastLines = new ArrayList<>();
        private final Map<String, Integer> lastScores = new HashMap<>();
        private Component lastHeader;
        private Component lastFooter;

        private Panel(Player player) {
            this.player = player;
            this.board = Bukkit.getScoreboardManager().getNewScoreboard();
            String title = plugin.getConfig().getString("hud.scoreboard.title", "&6&lGUAB");
            this.objective = board.registerNewObjective("guab", Criteria.DUMMY, parse(title));
            this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
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
            Objective objective = board.registerNewObjective("guab_points", Criteria.DUMMY, parse(title));
            objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
            return objective;
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
            refreshBelowName();
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
                int value = points.get(online.getUniqueId());
                if (lastScores.getOrDefault(online.getName(), Integer.MIN_VALUE) == value) {
                    continue;
                }
                Score score = belowName.getScore(online.getName());
                score.setScore(Math.max(0, value));
                lastScores.put(online.getName(), value);
            }
        }

        private void setSidebarVisible(boolean visible) {
            if (visible && plugin.getConfig().getBoolean("hud.scoreboard.enabled", true)) {
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            } else {
                board.clearSlot(DisplaySlot.SIDEBAR);
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
