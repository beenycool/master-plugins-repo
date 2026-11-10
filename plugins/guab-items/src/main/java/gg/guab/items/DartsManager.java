package gg.guab.items;

import gg.guab.core.HudChannel;
import gg.guab.core.PointsService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the dartboard: live legs, aim scoring, HUD, career records and the
 * showpiece effects around the board.
 */
public final class DartsManager implements Listener {

    private final GuabItemsPlugin plugin;
    private final DartsStatsStore store;
    private final DartsRoom room;
    private final Map<UUID, DartsGame> games = new HashMap<>();
    private final Map<UUID, DartsMatch> matches = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Preset> presets = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, BukkitTask> drawTasks = new HashMap<>();
    private final Map<UUID, Long> drawStarted = new HashMap<>();
    private final NamespacedKey displayKey;
    private final NamespacedKey dartKey;

    private DartsConfig config;
    private TextDisplay display;
    private BukkitTask hudTask;
    private BukkitTask saveTask;

    public DartsManager(GuabItemsPlugin plugin) {
        this.plugin = plugin;
        this.store = new DartsStatsStore(plugin);
        this.room = new DartsRoom(plugin);
        this.displayKey = new NamespacedKey(plugin, "darts_board_display");
        this.dartKey = new NamespacedKey(plugin, "darts_throw_display");
        this.config = DartsConfig.load(plugin);
    }

    public void start() {
        store.load();
        removeLeftoverDisplays();
        if (room.world() == null) {
            plugin.getLogger().warning("The darts room world could not be created.");
        }
        hudTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick,
                20L, Math.max(1, config.hudIntervalTicks()));
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, store::saveIfDirty,
                20L * 60, 20L * 60);
    }

    public void stop() {
        if (hudTask != null) {
            hudTask.cancel();
            hudTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        cancelAllDraws();
        clearBossBars();
        store.save();
        // Eject both players and any visitors left in the darts world, and take
        // the bow/arrows back so shutdown cannot leak the kit into the pub.
        room.ejectVisitors();
        room.removeLiveBoard();
        games.clear();
        matches.clear();
        invites.clear();
        removeDisplay();
    }

    public void reload() {
        endAll("The board was reset while the rules were being changed.");
        this.config = DartsConfig.load(plugin);
        removeLeftoverDisplays();
        updateDisplay();
    }

    public DartsConfig config() {
        return config;
    }

    public DartsRoom room() {
        return room;
    }

    public DartsStatsStore.Stats stats(UUID player) {
        return store.stats(player);
    }

    /** Public record lookup by name, including offline players from darts.yml. */
    public DartsStatsStore.Stats statsByName(String name) {
        return store.findByName(name);
    }

    public List<Map.Entry<String, Integer>> top(String metric, int limit) {
        return store.top(metric, limit);
    }

    public boolean isPlaying(UUID player) {
        return games.containsKey(player);
    }

    /** The live leg for a player, or null. Read-only status/dialog use. */
    public DartsGame game(UUID player) {
        return games.get(player);
    }

    /** Sidebar lines for the shared HUD, or an empty list when idle. */
    public List<String> hudLines(Player player) {
        DartsGame game = games.get(player.getUniqueId());
        if (game == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("&8Darts");
        DartsMatch match = matches.get(player.getUniqueId());
        if (match != null) {
            DartsGame other = games.get(match.other(player.getUniqueId()));
            lines.add("&7Score &8» &f" + game.score() + " vs "
                    + (other == null ? "?" : other.score()));
            lines.add(match.active().equals(player.getUniqueId())
                    ? "&7Turn &8» &aYours"
                    : "&7Turn &8» &cWaiting");
        } else {
            lines.add("&7Leg &8» &f" + game.mode());
            lines.add("&7Left &8» &f" + game.score());
            lines.add("&7Darts &8» &f" + game.dartsLeft());
        }
        String hint = game.checkoutHint();
        if (hint != null) {
            lines.add("&7Checkout &8» &a" + hint);
        }
        return lines;
    }

    public boolean challenge(Player from, Player target) {
        if (!config.enabled()) {
            actionBar(from, Component.text("The dartboard is closed.", NamedTextColor.RED));
            return false;
        }
        if (from.getUniqueId().equals(target.getUniqueId())) {
            actionBar(from, Component.text("You cannot challenge yourself.", NamedTextColor.RED));
            return false;
        }
        if (games.containsKey(from.getUniqueId()) || games.containsKey(target.getUniqueId())) {
            actionBar(from, Component.text("One of you is already on the board.", NamedTextColor.YELLOW));
            return false;
        }
        invites.put(from.getUniqueId(),
                new Invite(target.getUniqueId(), System.currentTimeMillis() + 60_000L));
        send(from, "Challenged " + target.getName() + " to a 1v1. Waiting for them to accept.", NamedTextColor.GOLD);
        target.sendMessage(Component.text("[Darts] ", NamedTextColor.GOLD)
                .append(Component.text(from.getName() + " challenges you to a 1v1. ", NamedTextColor.WHITE))
                .append(Component.text("[Accept]", NamedTextColor.GREEN)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                                "/darts accept " + from.getName()))));
        return true;
    }

    public boolean accept(Player target, Player challenger) {
        Invite invite = invites.get(challenger.getUniqueId());
        if (invite == null || !invite.target().equals(target.getUniqueId())
                || invite.expiresAt() < System.currentTimeMillis()) {
            invites.remove(challenger.getUniqueId());
            actionBar(target, Component.text("That challenge has expired.", NamedTextColor.RED));
            return false;
        }
        invites.remove(challenger.getUniqueId());
        if (games.containsKey(challenger.getUniqueId()) || games.containsKey(target.getUniqueId())) {
            actionBar(target, Component.text("One of you is already on the board.", NamedTextColor.YELLOW));
            return false;
        }
        return startMatch(challenger, target);
    }

    private boolean startMatch(Player first, Player second) {
        // A 1v1 claims two of the four oches. Never let it overfill the room.
        if (!DartsSlots.canStartMatch(games.size())) {
            actionBar(first, Component.text("The darts room is full (4 players).", NamedTextColor.YELLOW));
            actionBar(second, Component.text("The darts room is full (4 players).", NamedTextColor.YELLOW));
            return false;
        }
        int mode = config.defaultMode();
        int legs = Math.max(1, config.legsToWin());
        DartsGame firstGame = new DartsGame(first.getUniqueId(), first.getName(), mode, legs, config.beginnerAssist());
        DartsGame secondGame = new DartsGame(second.getUniqueId(), second.getName(), mode, legs, config.beginnerAssist());
        games.put(first.getUniqueId(), firstGame);
        games.put(second.getUniqueId(), secondGame);
        DartsMatch match = new DartsMatch(first.getUniqueId(), second.getUniqueId(), mode, legs);
        matches.put(first.getUniqueId(), match);
        matches.put(second.getUniqueId(), match);
        store.recordLegStart(first.getUniqueId(), first.getName());
        store.recordLegStart(second.getUniqueId(), second.getName());

        if (!room.enter(first)) {
            rollbackStart(first, firstGame, true);
            rollbackStart(second, secondGame, true);
            return false;
        }
        if (!room.enter(second)) {
            rollbackStart(first, firstGame, true);
            rollbackStart(second, secondGame, true);
            return false;
        }
        presets.put(first.getUniqueId(), new Preset(mode, legs));
        presets.put(second.getUniqueId(), new Preset(mode, legs));
        updateDisplay();

        first.showTitle(Title.title(
                Component.text("1V1", NamedTextColor.GOLD),
                Component.text(mode + " · first to " + legs, NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(180), Duration.ofMillis(950), Duration.ofMillis(350))));
        second.showTitle(Title.title(
                Component.text("1V1", NamedTextColor.GOLD),
                Component.text("vs " + first.getName(), NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(180), Duration.ofMillis(950), Duration.ofMillis(350))));
        send(first, "1v1 vs " + second.getName() + ". You throw first.", NamedTextColor.GOLD);
        send(second, "1v1 vs " + first.getName() + ". " + first.getName() + " throws first.", NamedTextColor.GOLD);
        showHud(first, firstGame);
        showHud(second, secondGame);
        return true;
    }

    private void endMatch(DartsMatch match, Player winner, String reason) {
        UUID firstId = match.first();
        UUID secondId = match.second();
        Player first = Bukkit.getPlayer(firstId);
        Player second = Bukkit.getPlayer(secondId);
        if (first != null) {
            removeBossBar(first);
            cancelDraw(first);
        }
        if (second != null) {
            removeBossBar(second);
            cancelDraw(second);
        }
        games.remove(firstId);
        games.remove(secondId);
        matches.remove(firstId);
        matches.remove(secondId);
        if (first != null && room.isInRoom(first)) {
            room.exit(first);
        }
        if (second != null && room.isInRoom(second)) {
            room.exit(second);
        }
        if (winner != null) {
            String loser = winner.getUniqueId().equals(firstId)
                    ? nameOf(secondId) : nameOf(firstId);
            send(winner, "You won the 1v1 against " + loser + ".", NamedTextColor.GOLD);
            Player other = Bukkit.getPlayer(match.other(winner.getUniqueId()));
            if (other != null) {
                send(other, winner.getName() + " won the 1v1.", NamedTextColor.RED);
            }
        } else if (reason != null) {
            if (first != null) {
                send(first, reason, NamedTextColor.GRAY);
            }
            if (second != null) {
                send(second, reason, NamedTextColor.GRAY);
            }
        }
        updateDisplay();
    }

    private String nameOf(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player != null) {
            return player.getName();
        }
        DartsGame game = games.get(id);
        return game == null ? "them" : game.playerName();
    }

    public void openMenu(Player player) {
        if (!config.enabled()) {
            actionBar(player, Component.text("The dartboard is closed.", NamedTextColor.RED));
            return;
        }
        if (isPlaying(player.getUniqueId())) {
            DartsDialog.openActive(plugin, player);
            return;
        }
        DartsDialog.openMenu(plugin, player);
    }

    public void startDefault(Player player) {
        startGame(player, config.defaultMode(), config.legsToWin());
    }

    public boolean startGame(Player player, int mode, int legsToWin) {
        return startGame(player, mode, legsToWin, false);
    }

    /**
     * Starts a solo leg. Practice legs use the same board but deliberately skip
     * career stats, rewards and announcements.
     */
    public boolean startGame(Player player, int mode, int legsToWin, boolean practice) {
        if (!config.enabled()) {
            actionBar(player, Component.text("The dartboard is closed.", NamedTextColor.RED));
            return false;
        }
        if (!player.hasPermission("guab.pub.darts.use")) {
            actionBar(player, Component.text("You are not allowed to play darts.", NamedTextColor.RED));
            return false;
        }
        if (games.containsKey(player.getUniqueId())) {
            actionBar(player, Component.text("You already have a leg on the board.", NamedTextColor.YELLOW));
            return false;
        }
        if (!DartsSlots.canStartSingle(games.size())) {
            actionBar(player, Component.text("The darts room is full (4 players).", NamedTextColor.YELLOW));
            return false;
        }
        int normalisedMode = mode == 101 || mode == 301 || mode == 501 ? mode : config.defaultMode();
        int normalisedLegs = Math.clamp(legsToWin, 1, 9);
        DartsGame game = new DartsGame(player.getUniqueId(), player.getName(), normalisedMode,
                normalisedLegs, config.beginnerAssist(), practice);
        games.put(player.getUniqueId(), game);
        // Record first, then roll the record back if the room cannot take the
        // player. No stats or HUD state may survive a failed entry.
        if (!practice) {
            store.recordLegStart(player.getUniqueId(), player.getName());
        }

        if (!room.enter(player)) {
            rollbackStart(player, game, !practice);
            return false;
        }
        presets.put(player.getUniqueId(), new Preset(normalisedMode, normalisedLegs));
        updateDisplay();

        boolean beginner = config.beginnerAssist();
        player.showTitle(Title.title(
                Component.text(practice ? "PRACTICE" : "GAME ON",
                        practice ? NamedTextColor.AQUA : NamedTextColor.GOLD),
                Component.text(normalisedMode + (beginner ? " · beginner" : " · double out")
                                + (practice ? " · no records" : ""),
                        NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(900), Duration.ofMillis(400))));
        GuabSounds.play(player, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.8f, 1.4f);
        send(player, (practice ? "Practice leg: " : "Leg on: ") + normalisedMode
                + ". Take the bow, aim at the target. "
                + (beginner ? "Three darts, no busts, any dart can finish. Good luck."
                            : "Three darts, double out. Good luck.")
                + (practice ? " This leg will not count on your record." : ""), NamedTextColor.GOLD);
        showHud(player, game);
        return true;
    }

    private void rollbackStart(Player player, DartsGame game, boolean recordedStats) {
        games.remove(player.getUniqueId());
        matches.remove(player.getUniqueId());
        removeBossBar(player);
        cancelDraw(player);
        clearBar(player);
        if (recordedStats) {
            store.rollbackLegStart(player.getUniqueId());
        }
        if (room.isInRoom(player)) {
            room.exit(player);
        }
        updateDisplay();
    }

    /** Resume a leg after re-opening the board dialog. */
    public void resume(Player player) {
        DartsGame game = games.get(player.getUniqueId());
        if (game == null) {
            send(player, "You are not in a leg. Start one with /darts start.", NamedTextColor.GRAY);
            return;
        }
        if (!room.isInRoom(player) && !room.enter(player)) {
            return;
        }
        showHud(player, game);
        send(player, "Back on the oche. " + game.score() + " left.", NamedTextColor.GREEN);
    }

    /** The last mode/legs this player started, or null. */
    public Preset lastPreset(UUID player) {
        return presets.get(player);
    }

    /** Console-friendly status lines for {@code /darts status}. */
    public List<String> statusLines(Player player) {
        DartsGame game = games.get(player.getUniqueId());
        if (game == null) {
            return List.of("You are not on the oche. Start a leg with /darts start.");
        }
        List<String> lines = new ArrayList<>();
        lines.add((game.practice() ? "Practice" : "Ranked") + " " + game.mode()
                + " · leg " + game.legNumber() + "/" + game.legsToWin());
        lines.add("Score " + game.score() + " left · " + game.dartsLeft() + " darts this visit");
        DartsMatch match = matches.get(player.getUniqueId());
        if (match != null) {
            DartsGame other = games.get(match.other(player.getUniqueId()));
            lines.add("1v1 vs " + nameOf(match.other(player.getUniqueId())) + " ("
                    + (other == null ? "?" : other.score()) + " left) · "
                    + (match.active().equals(player.getUniqueId()) ? "your turn" : "waiting"));
        }
        double units = plugin.pub() == null ? 0 : plugin.pub().drunkUnits(player.getUniqueId());
        lines.add(String.format(Locale.ROOT, "Drunk meter %.1f units · %s", units,
                plugin.pub() == null ? "Sober" : plugin.pub().tierLabel(units)));
        if (!room.isInRoom(player)) {
            lines.add("You are away from the oche. The board dialog can resume you.");
        }
        return lines;
    }

    /** Arrow impact in the dedicated darts room. */
    public void scoreArrow(Player player, Location point) {
        DartsGame game = games.get(player.getUniqueId());
        Location centre = room.targetCentre();
        if (game == null || centre == null || point == null) {
            return;
        }
        resolveThrow(player, game, point, centre, BlockFace.SOUTH, DartsRoom.BOARD_RADIUS);
    }

    /** Legacy pub-board throw, kept as a fallback if the room is unavailable. */
    public void throwDart(Player player, Location point) {
        DartsGame game = games.get(player.getUniqueId());
        if (game == null) {
            openMenu(player);
            return;
        }
        if (point == null) {
            actionBar(player, Component.text("Aim at the target and shoot again.", NamedTextColor.GRAY));
            return;
        }
        Location centre = boardCentre();
        if (centre == null || point.getWorld() == null || !point.getWorld().equals(centre.getWorld())) {
            actionBar(player, Component.text("The board is not in this world.", NamedTextColor.RED));
            return;
        }
        if (!player.getWorld().equals(centre.getWorld())
                || player.getLocation().distanceSquared(centre)
                > config.maxDistance() * config.maxDistance()) {
            actionBar(player, Component.text("Stand closer to the oche.", NamedTextColor.RED));
            return;
        }
        resolveThrow(player, game, point, centre, config.face(), config.boardRadius());
    }

    private void resolveThrow(Player player, DartsGame game, Location point,
                              Location centre, BlockFace face, double radius) {
        long now = System.currentTimeMillis();
        long remaining = cooldowns.getOrDefault(player.getUniqueId(), 0L) - now;
        if (remaining > 0L) {
            actionBar(player, Component.text("Steady... " + remaining + "ms", NamedTextColor.GRAY));
            return;
        }
        DartsMatch match = matches.get(player.getUniqueId());
        if (match != null && !match.active().equals(player.getUniqueId())) {
            actionBar(player, Component.text("Wait for " + nameOf(match.active()) + "'s visit.",
                    NamedTextColor.GRAY));
            return;
        }
        cooldowns.put(player.getUniqueId(), now + config.cooldownMillis());

        double drunk = plugin.pub() == null ? 0 : plugin.pub().impairment(player.getUniqueId()) * 10.0;
        double wobble = Math.min(config.wobbleMax(), config.wobbleSober() + drunk * config.wobblePerDrunk());
        DartsGeometry.Hit hit = DartsGeometry.score(centre, face, radius, point, config, wobble);

        DartsGame.Result result = game.throwDart(hit);
        boolean practice = game.practice();
        if (!practice) {
            store.recordThrow(player.getUniqueId());
            if (result.kind() == DartsGame.Kind.TURN_END) {
                store.recordVisit(player.getUniqueId(), result.turnTotal());
            }
            if (result.oneEighty()) {
                store.record180(player.getUniqueId());
            }
            if (result.kind() == DartsGame.Kind.CHECKOUT) {
                store.recordCheckout(player.getUniqueId(), result.legDarts(), result.checkout());
            }
        }

        showHud(player, game);
        present(player, game, result, point);
        if (result.kind() != DartsGame.Kind.BUST) {
            if (result.hit().score() > 0 && result.scoreAfter() == result.scoreBefore()) {
                send(player, "No score — that would have bust. "
                        + result.scoreAfter() + " left", NamedTextColor.GRAY);
            } else {
                String hitText = result.hit().score() == 0
                        ? "Missed"
                        : "Hit " + result.hit().label() + " for " + result.hit().score();
                send(player, hitText + " · " + result.scoreAfter() + " left", NamedTextColor.WHITE);
            }
        }

        if (result.kind() == DartsGame.Kind.CHECKOUT) {
            int reward = practice ? 0 : reward(player, game, result);
            String payout = reward > 0 ? "  (+" + reward + " points)" : "";
            if (!practice) {
                announce(player, player.getName() + " checked out " + result.checkout()
                        + " in " + result.legDarts() + " darts" + payout);
            }
            if (match != null) {
                if (result.matchWon()) {
                    endMatch(match, player, null);
                    GuabSounds.play(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0f, 1.0f);
                } else {
                    UUID opponentId = match.other(player.getUniqueId());
                    DartsGame opponent = games.get(opponentId);
                    if (opponent != null) {
                        opponent.resetLeg();
                        store.recordLegStart(opponentId, opponent.playerName());
                    }
                    store.recordLegStart(player.getUniqueId(), player.getName());
                    match.setActive(opponentId);
                    send(player, "Leg won. " + nameOf(opponentId) + " throws first next leg.",
                            NamedTextColor.GREEN);
                    Player other = Bukkit.getPlayer(opponentId);
                    if (other != null) {
                        send(other, player.getName() + " won the leg. Your throw.", NamedTextColor.GOLD);
                        if (opponent != null) {
                            showHud(other, opponent);
                        }
                    }
                }
            } else if (result.matchWon()) {
                games.remove(player.getUniqueId());
                removeBossBar(player);
                cancelDraw(player);
                updateDisplay();
                send(player, "Match won " + result.legsWon() + "–0. The bar is impressed.", NamedTextColor.GOLD);
                GuabSounds.play(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0f, 1.0f);
                if (room.isInRoom(player)) {
                    room.exit(player);
                }
            } else {
                if (!practice) {
                    store.recordLegStart(player.getUniqueId(), player.getName());
                }
                send(player, (practice ? "Practice leg won. " : "Leg won. ")
                        + "Next leg: " + game.mode() + ". Stay on the oche.", NamedTextColor.GREEN);
            }
        } else if (result.kind() == DartsGame.Kind.BUST) {
            send(player, "Bust. Back to " + result.scoreAfter() + ".", NamedTextColor.RED);
        } else if (result.oneEighty() && !practice) {
            announce(player, player.getName() + " hit ONE HUNDRED AND EIGHTY!");
        }

        if (match != null && (result.kind() == DartsGame.Kind.TURN_END
                || result.kind() == DartsGame.Kind.BUST)) {
            UUID opponentId = match.other(player.getUniqueId());
            match.setActive(opponentId);
            send(player, "Visit over. " + nameOf(opponentId) + "'s throw.", NamedTextColor.GRAY);
            Player other = Bukkit.getPlayer(opponentId);
            if (other != null) {
                send(other, "Your turn.", NamedTextColor.GREEN);
            }
        }

        updateDisplay();
    }

    public void endGame(Player player, String reason) {
        removeBossBar(player);
        cancelDraw(player);
        DartsMatch match = matches.get(player.getUniqueId());
        if (match != null) {
            // Leaving a 1v1 is a forfeit, exactly like disconnecting.
            UUID opponentId = match.other(player.getUniqueId());
            Player other = Bukkit.getPlayer(opponentId);
            store.recordForfeitWin(opponentId);
            send(player, reason, NamedTextColor.GRAY);
            if (other != null) {
                send(other, player.getName() + " left the 1v1. You win by forfeit.",
                        NamedTextColor.GREEN);
            }
            endMatch(match, null, null);
            return;
        }
        if (games.remove(player.getUniqueId()) != null) {
            send(player, reason, NamedTextColor.GRAY);
            if (room.isInRoom(player)) {
                room.exit(player);
            }
            updateDisplay();
        }
    }

    public void endAll(String reason) {
        cancelAllDraws();
        clearBossBars();
        if (games.isEmpty()) {
            return;
        }
        for (UUID id : new ArrayList<>(games.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                send(player, reason, NamedTextColor.GRAY);
                if (room.isInRoom(player)) {
                    room.exit(player);
                }
            }
        }
        games.clear();
        matches.clear();
        invites.clear();
        updateDisplay();
    }

    public boolean isBoard(Block block, Location point) {
        if (block == null || block.getWorld() == null) {
            return false;
        }
        if (!block.getWorld().getName().equals(config.world())) {
            return false;
        }
        if (block.getX() == config.x() && block.getY() == config.y() && block.getZ() == config.z()) {
            return true;
        }
        Location centre = boardCentre();
        return point != null && centre != null && point.getWorld() != null
                && point.getWorld().equals(centre.getWorld())
                && point.distanceSquared(centre) <= (config.boardRadius() + 0.2) * (config.boardRadius() + 0.2);
    }

    public void setBoard(Block block, BlockFace face) {
        if (face != BlockFace.NORTH && face != BlockFace.EAST
                && face != BlockFace.SOUTH && face != BlockFace.WEST) {
            face = BlockFace.EAST;
        }
        plugin.getConfig().set("pub.darts.board.world", block.getWorld().getName());
        plugin.getConfig().set("pub.darts.board.x", block.getX());
        plugin.getConfig().set("pub.darts.board.y", block.getY());
        plugin.getConfig().set("pub.darts.board.z", block.getZ());
        plugin.getConfig().set("pub.darts.board.face", face.name());
        plugin.saveConfig();
        reload();
    }

    public Location boardCentre() {
        World world = Bukkit.getWorld(config.world());
        if (world == null) {
            return null;
        }
        return DartsGeometry.centre(new Location(world, config.x(), config.y(), config.z()), config.face());
    }

    public int balance(UUID player) {
        PointsService points = points();
        return points == null ? 0 : points.get(player);
    }

    private void tick() {
        if (games.isEmpty()) {
            return;
        }
        for (DartsGame game : new ArrayList<>(games.values())) {
            Player player = Bukkit.getPlayer(game.playerId());
            if (player == null || !player.isOnline()) {
                games.remove(game.playerId());
                bossBars.remove(game.playerId());
                continue;
            }
            showHud(player, game);
        }
        updateDisplay();
    }

    private void showHud(Player player, DartsGame game) {
        DartsMatch match = matches.get(player.getUniqueId());
        if (match != null) {
            showMatchHud(player, match, game);
            return;
        }
        String hint = game.checkoutHint();
        Component line = Component.text("DARTS ", NamedTextColor.GOLD)
                .append(Component.text(String.valueOf(game.mode()), NamedTextColor.YELLOW))
                .append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(game.score() + " left", NamedTextColor.WHITE))
                .append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(game.dartsLeft() + " darts", NamedTextColor.AQUA))
                .append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                .append(Component.text("L" + game.legNumber() + "/" + game.legsToWin(), NamedTextColor.GRAY))
                .append(drunkIndicator(player))
                .append(game.practice()
                        ? Component.text("  ·  ", NamedTextColor.DARK_GRAY)
                                .append(Component.text("PRACTICE", NamedTextColor.AQUA))
                        : Component.empty());
        if (hint != null) {
            line = line.append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(hint, NamedTextColor.GREEN));
        }
        actionBar(player, line, HudChannel.PRIORITY_HINT,
                Math.max(40, config.hudIntervalTicks() * 2));
        updateBossBar(player, game, line);
    }

    private void showMatchHud(Player player, DartsMatch match, DartsGame game) {
        UUID otherId = match.other(player.getUniqueId());
        DartsGame other = games.get(otherId);
        boolean turn = match.active().equals(player.getUniqueId());
        Component line = Component.text("DARTS 1V1 ", NamedTextColor.GOLD)
                .append(Component.text(match.mode + " ", NamedTextColor.YELLOW))
                .append(Component.text("· You " + game.score(), NamedTextColor.WHITE))
                .append(Component.text(" · " + nameOf(otherId) + " "
                        + (other == null ? "?" : other.score()), NamedTextColor.GRAY))
                .append(Component.text(" · ", NamedTextColor.DARK_GRAY))
                .append(Component.text(turn ? "YOUR TURN" : "waiting",
                        turn ? NamedTextColor.GREEN : NamedTextColor.RED))
                .append(drunkIndicator(player));
        if (turn) {
            line = line.append(Component.text(" · " + game.dartsLeft() + " darts", NamedTextColor.AQUA));
        }
        actionBar(player, line, HudChannel.PRIORITY_HINT,
                Math.max(40, config.hudIntervalTicks() * 2));
        updateBossBar(player, game, line);
    }

    private Component drunkIndicator(Player player) {
        double units = plugin.pub() == null ? 0 : plugin.pub().drunkUnits(player.getUniqueId());
        String tier = plugin.pub() == null ? "Sober" : plugin.pub().tierLabel(units);
        return Component.text("  ·  ", NamedTextColor.DARK_GRAY)
                .append(Component.text(String.format(Locale.ROOT, "%.1f", units) + "u " + tier,
                        NamedTextColor.AQUA));
    }

    private void updateBossBar(Player player, DartsGame game, Component line) {
        BossBar bar = bossBars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(line, 1.0f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_10);
            bossBars.put(player.getUniqueId(), bar);
        }
        float progress = Math.clamp(game.score() / (float) game.mode(), 0.0f, 1.0f);
        BossBar.Color colour = progress > 0.5f ? BossBar.Color.GREEN
                : progress > 0.2f ? BossBar.Color.YELLOW : BossBar.Color.RED;
        bar.name(line);
        bar.progress(progress);
        bar.color(colour);
        player.showBossBar(bar);
    }

    private void removeBossBar(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    private void clearBossBars() {
        for (UUID id : new ArrayList<>(bossBars.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                removeBossBar(player);
            } else {
                bossBars.remove(id);
            }
        }
    }

    private void present(Player player, DartsGame game, DartsGame.Result result, Location point) {
        boolean inRoom = room.isInRoom(player);
        // Effects must live in the same world as the arrow. When the leg is
        // played in the darts room, the pub board world is the wrong place.
        Location centre = inRoom ? room.targetCentre() : boardCentre();
        World effectWorld = point == null ? null : point.getWorld();
        if (effectWorld == null && centre != null) {
            effectWorld = centre.getWorld();
        }
        if (effectWorld != null && point != null) {
            effectWorld.spawnParticle(Particle.DUST, point, 18, 0.18, 0.18, 0.18, 0.0,
                    new Particle.DustOptions(Color.fromRGB(0xF4C542), 0.9f));
            effectWorld.spawnParticle(Particle.CRIT, point, 10, 0.12, 0.12, 0.12, 0.02);
            if (!inRoom) {
                spawnDart(point);
            }
        }

        if (result.kind() == DartsGame.Kind.BUST) {
            GuabSounds.play(player, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.8f, 0.6f);
        } else if (result.hit().score() == 0) {
            GuabSounds.play(player, Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 0.5f, 0.8f);
        } else if (result.kind() == DartsGame.Kind.CHECKOUT) {
            GuabSounds.play(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.9f, 1.2f);
            celebration(centre, result.oneEighty() ? 3 : 2);
        } else if (result.oneEighty()) {
            player.showTitle(Title.title(
                    Component.text("180!", NamedTextColor.GOLD),
                    Component.text("three in the treble twenty", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1200), Duration.ofMillis(350))));
            GuabSounds.play(player, Sound.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 1.0f, 0.8f);
            GuabSounds.play(player, GuabSounds.ONE_EIGHTY, 1.0f, 1.0f);
            for (Player nearby : player.getWorld().getPlayers()) {
                if (nearby.equals(player)) {
                    continue;
                }
                if (nearby.getLocation().distanceSquared(player.getLocation()) <= 24.0 * 24.0) {
                    GuabSounds.play(nearby, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.7f, 1.2f);
                }
            }
            celebration(centre, 2);
        } else if (result.hit().number() == 20 && result.hit().multiplier() == 3) {
            player.showTitle(Title.title(
                    Component.text("TRIPLE 20", NamedTextColor.GOLD),
                    Component.text("60", NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(650), Duration.ofMillis(200))));
            GuabSounds.play(player, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0f, 1.6f);
        } else if (result.hit().isBullseye()) {
            player.showTitle(Title.title(
                    Component.text("BULLSEYE", NamedTextColor.RED),
                    Component.text("50", NamedTextColor.GOLD),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(700), Duration.ofMillis(250))));
            GuabSounds.play(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 0.7f);
        } else {
            GuabSounds.play(player, Sound.ENTITY_ARROW_HIT, SoundCategory.PLAYERS, 0.9f, 1.2f);
        }

        int barPriority = result.kind() == DartsGame.Kind.CHECKOUT
                || result.kind() == DartsGame.Kind.BUST || result.oneEighty()
                ? HudChannel.PRIORITY_POINTS : HudChannel.PRIORITY_HINT;
        if (result.kind() == DartsGame.Kind.BUST) {
            actionBar(player, Component.text("BUST", NamedTextColor.RED)
                    .append(Component.text("  ·  back to " + result.scoreAfter(), NamedTextColor.GRAY)),
                    barPriority, 45);
        } else if (result.kind() == DartsGame.Kind.CHECKOUT) {
            actionBar(player, Component.text("CHECKOUT " + result.checkout()
                    + " in " + result.legDarts() + " darts", NamedTextColor.GOLD), barPriority, 45);
        } else if (result.kind() == DartsGame.Kind.TURN_END) {
            actionBar(player, Component.text("Visit " + result.turnTotal(), NamedTextColor.WHITE)
                    .append(Component.text("  ·  " + result.scoreAfter() + " left", NamedTextColor.GRAY))
                    .append(Component.text("  ·  new turn", NamedTextColor.AQUA)),
                    barPriority, 45);
        } else {
            actionBar(player, Component.text(result.hit().label(), NamedTextColor.WHITE)
                    .append(Component.text("  ·  " + result.scoreAfter() + " left", NamedTextColor.GRAY))
                    .append(Component.text("  ·  darts " + game.dartsLeft() + "/3", NamedTextColor.AQUA)),
                    barPriority, 45);
        }
    }

    private void spawnDart(Location point) {
        if (point.getWorld() == null) {
            return;
        }
        Location location = point.clone().add(config.face().getDirection().clone().multiply(0.08));
        ItemDisplay dart = point.getWorld().spawn(location, ItemDisplay.class, entity -> {
            entity.setItemStack(new ItemStack(Material.ARROW));
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setViewRange(0.8f);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setTransformation(new Transformation(
                    new Vector3f(), new AxisAngle4f(),
                    new Vector3f(0.65f, 0.65f, 0.65f), new AxisAngle4f()));
            entity.getPersistentDataContainer().set(dartKey, PersistentDataType.BYTE, (byte) 1);
        });
        Bukkit.getScheduler().runTaskLater(plugin, dart::remove, 60L);
    }

    private void celebration(Location centre, int rockets) {
        if (centre == null || centre.getWorld() == null) {
            return;
        }
        World world = centre.getWorld();
        for (int i = 0; i < rockets; i++) {
            double offset = i == 0 ? 0.0 : (i % 2 == 0 ? 0.8 : -0.8);
            Location location = centre.clone().add(offset, 0.4 + i * 0.2, 0.2);
            Firework firework = world.spawn(location, Firework.class, entity -> {
                FireworkMeta meta = entity.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder()
                        .with(FireworkEffect.Type.BURST)
                        .withColor(Color.YELLOW, Color.ORANGE)
                        .withFade(Color.WHITE)
                        .trail(true)
                        .flicker(true)
                        .build());
                meta.setPower(0);
                entity.setFireworkMeta(meta);
                entity.setShotAtAngle(true);
            });
            firework.detonate();
        }
    }

    private int reward(Player player, DartsGame game, DartsGame.Result result) {
        if (!config.rewards()) {
            return 0;
        }
        int base = switch (game.mode()) {
            case 101 -> config.base101();
            case 301 -> config.base301();
            default -> config.base501();
        };
        int par = switch (game.mode()) {
            case 101 -> config.par101();
            case 301 -> config.par301();
            default -> config.par501();
        };
        int underPar = Math.max(0, par - result.legDarts()) * config.underParPerDart();
        int checkout = (int) Math.round(result.checkout() * config.checkoutPerPoint());
        int total = Math.min(config.maxReward(), base + underPar + checkout);
        if (total > 0) {
            PointsService points = points();
            if (points != null) {
                points.add(player.getUniqueId(), total);
            }
        }
        return total;
    }

    private void announce(Player source, String message) {
        Location centre = boardCentre();
        if (centre == null) {
            return;
        }
        Component line = Component.text("[Darts] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.WHITE));
        Set<UUID> sent = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getName().equals("guab_darts")
                    && isPlaying(player.getUniqueId())) {
                player.sendMessage(line);
                sent.add(player.getUniqueId());
            } else if (player.getWorld().equals(centre.getWorld())
                    && player.getLocation().distanceSquared(centre) <= 48.0 * 48.0) {
                player.sendMessage(line);
                sent.add(player.getUniqueId());
            }
        }
        // The room broadcast already reached the thrower when the leg is in
        // guab_darts; only add the fallback when nobody in their world heard it.
        if (!sent.contains(source.getUniqueId())) {
            source.sendMessage(line);
        }
    }

    private void updateDisplay() {
        if (!config.enabled() || games.isEmpty()) {
            removeDisplay();
            room.updateLiveBoard(games.values());
            return;
        }
        Location centre = boardCentre();
        if (centre == null || centre.getWorld() == null) {
            removeDisplay();
            room.updateLiveBoard(games.values());
            return;
        }
        World world = centre.getWorld();
        if (display == null || !display.isValid()) {
            Location location = centre.clone().add(0, 1.25, 0)
                    .add(config.face().getDirection().clone().multiply(0.05));
            display = world.spawn(location, TextDisplay.class, entity -> {
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setSeeThrough(false);
                entity.setShadowed(true);
                entity.setPersistent(false);
                entity.setViewRange(0.9f);
                entity.setDefaultBackground(false);
                entity.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                entity.setLineWidth(200);
                entity.setAlignment(TextDisplay.TextAlignment.CENTER);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.getPersistentDataContainer().set(displayKey, PersistentDataType.BYTE, (byte) 1);
            });
        }
        DartsGame game = games.values().iterator().next();
        String hint = game.checkoutHint();
        Component text = Component.text("WETHERSPOONS DARTS", NamedTextColor.GOLD)
                .append(Component.text("\n" + game.playerName() + "  ·  " + game.score() + " left",
                        NamedTextColor.WHITE))
                .append(Component.text("\nLeg " + game.legNumber() + "/" + game.legsToWin()
                        + "  ·  darts " + game.dartsLeft() + "/3", NamedTextColor.GRAY))
                .append(Component.text("\n" + (hint == null ? "no checkout" : "checkout  " + hint),
                        hint == null ? NamedTextColor.DARK_GRAY : NamedTextColor.GREEN));
        display.text(text);
        room.updateLiveBoard(games.values());
    }

    private void removeDisplay() {
        if (display != null) {
            display.remove();
            display = null;
        }
    }

    private void removeLeftoverDisplays() {
        Location centre = boardCentre();
        if (centre == null || centre.getWorld() == null) {
            return;
        }
        for (var entity : centre.getWorld().getNearbyEntities(centre, 6, 6, 6)) {
            if (entity instanceof TextDisplay text
                    && text.getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE)) {
                text.remove();
            }
            if (entity instanceof ItemDisplay item
                    && item.getPersistentDataContainer().has(dartKey, PersistentDataType.BYTE)) {
                item.remove();
            }
        }
        display = null;
    }

    private PointsService points() {
        var registration = Bukkit.getServicesManager().getRegistration(PointsService.class);
        return registration == null ? null : registration.getProvider();
    }

    private void send(Player player, String message, NamedTextColor colour) {
        player.sendMessage(Component.text("[Darts] ", NamedTextColor.GOLD)
                .append(Component.text(message, colour)));
    }

    private void actionBar(Player player, Component message) {
        actionBar(player, message, HudChannel.PRIORITY_HINT, 30);
    }

    /**
     * Route darts text through the shared HudChannel so a checkout hint can win
     * over the idle HUD without stomping a blackjack timer.
     */
    private void actionBar(Player player, Component message, int priority, int durationTicks) {
        HudChannel channel = plugin.hudChannel();
        if (channel != null) {
            channel.send(player, message, priority, durationTicks);
            return;
        }
        if (plugin.preferences() != null
                && !plugin.preferences().actionbar(player.getUniqueId())) {
            return;
        }
        player.sendActionBar(message);
    }

    private void clearBar(Player player) {
        actionBar(player, Component.empty(), HudChannel.PRIORITY_HINT, 2);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrawStart(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!games.containsKey(player.getUniqueId())) {
            return;
        }
        if (!room.isDartsBow(player.getInventory().getItemInMainHand())) {
            return;
        }
        cancelDraw(player);
        drawStarted.put(player.getUniqueId(), System.currentTimeMillis());
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> showDraw(player), 0L, 2L);
        drawTasks.put(player.getUniqueId(), task);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !games.containsKey(player.getUniqueId())) {
            return;
        }
        cancelDraw(player);
        actionBar(player, Component.text("RELEASE!", NamedTextColor.GREEN),
                HudChannel.PRIORITY_POINTS, 20);
    }

    private void showDraw(Player player) {
        UUID id = player.getUniqueId();
        Long started = drawStarted.get(id);
        if (!player.isOnline() || !games.containsKey(id) || started == null
                || !room.isDartsBow(player.getInventory().getItemInMainHand())) {
            cancelDraw(player);
            return;
        }
        long elapsed = (System.currentTimeMillis() - started) / 50L;
        if (elapsed > 60L) {
            cancelDraw(player);
            return;
        }
        int filled = Math.clamp((elapsed * 10L) / 20L, 0, 10);
        Component line = Component.text("DRAW ", NamedTextColor.GRAY)
                .append(Component.text("▮".repeat(filled),
                        filled >= 10 ? NamedTextColor.GREEN : NamedTextColor.AQUA))
                .append(Component.text("▯".repeat(10 - filled), NamedTextColor.DARK_GRAY));
        actionBar(player, line, HudChannel.PRIORITY_HINT, 6);
    }

    private void cancelDraw(Player player) {
        BukkitTask task = drawTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        drawStarted.remove(player.getUniqueId());
    }

    private void cancelAllDraws() {
        for (BukkitTask task : new ArrayList<>(drawTasks.values())) {
            task.cancel();
        }
        drawTasks.clear();
        drawStarted.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cooldowns.remove(id);
        removeBossBar(event.getPlayer());
        cancelDraw(event.getPlayer());
        DartsMatch match = matches.get(id);
        if (match != null) {
            UUID otherId = match.other(id);
            Player other = Bukkit.getPlayer(otherId);
            if (other != null) {
                removeBossBar(other);
                cancelDraw(other);
                send(other, event.getPlayer().getName() + " left the 1v1.", NamedTextColor.GRAY);
                store.recordForfeitWin(otherId);
                if (room.isInRoom(other)) {
                    room.exit(other);
                }
            }
            games.remove(otherId);
            matches.remove(otherId);
            matches.remove(id);
        }
        if (games.remove(id) != null) {
            room.handleQuit(event.getPlayer());
            updateDisplay();
        }
    }

    private static final class DartsMatch {
        private final UUID first;
        private final UUID second;
        private final int mode;
        private final int legsToWin;
        private UUID active;

        private DartsMatch(UUID first, UUID second, int mode, int legsToWin) {
            this.first = first;
            this.second = second;
            this.mode = mode;
            this.legsToWin = legsToWin;
            this.active = first;
        }

        private UUID first() {
            return first;
        }

        private UUID second() {
            return second;
        }

        private UUID active() {
            return active;
        }

        private void setActive(UUID active) {
            this.active = active;
        }

        private UUID other(UUID id) {
            return id.equals(first) ? second : first;
        }
    }

    private record Invite(UUID target, long expiresAt) {
    }

    /** Last mode and leg count a player chose, replayed by /darts again. */
    record Preset(int mode, int legs) {
    }
}
