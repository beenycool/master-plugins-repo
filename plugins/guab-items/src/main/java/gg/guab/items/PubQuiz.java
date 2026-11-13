package gg.guab.items;

import gg.guab.core.PointsService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The pub quiz. A question goes out in chat; the first correct answer wins points.
 */
public final class PubQuiz implements Listener {

    private final GuabItemsPlugin plugin;
    private final List<QuizQuestion> questions = new ArrayList<>();
    private BukkitTask task;
    private final AtomicReference<QuizQuestion> active = new AtomicReference<>();
    private volatile long startedAt;
    private volatile long lastFinishedAt;
    private String lastQuestion;
    private boolean warnedThirty;
    private boolean warnedFive;

    public PubQuiz(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        loadQuestions();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        lastFinishedAt = System.currentTimeMillis();
        warnedThirty = false;
        warnedFive = false;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        active.set(null);
    }

    public boolean isActive() {
        return active.get() != null;
    }

    /** Re-reads the question list without interrupting a question already asked. */
    public void reloadQuestions() {
        loadQuestions();
    }

    public void begin(CommandSender sender) {
        if (!plugin.getConfig().getBoolean("pub.quiz.enabled", true)) {
            sender.sendMessage(prefix().append(Component.text("The pub quiz is closed.", NamedTextColor.RED)));
            return;
        }
        if (active.get() != null) {
            sender.sendMessage(prefix().append(Component.text("A question is already running.",
                    NamedTextColor.YELLOW)));
            return;
        }
        if (questions.isEmpty()) {
            sender.sendMessage(prefix().append(Component.text("No quiz questions are configured.",
                    NamedTextColor.RED)));
            return;
        }
        ask();
    }

    /** {@code /quiz stop}: cancel the current question without replacing it. */
    public void stopBy(CommandSender sender, String sourceName) {
        warnedThirty = false;
        warnedFive = false;
        QuizQuestion question = active.getAndSet(null);
        if (question == null) {
            sender.sendMessage(prefix().append(Component.text("No question is running.",
                    NamedTextColor.YELLOW)));
            return;
        }
        lastFinishedAt = System.currentTimeMillis();
        Bukkit.broadcast(prefix().append(Component.text(
                "Quiz stopped by " + sourceName + ". The answer was " + question.displayAnswer() + ".",
                NamedTextColor.GRAY)));
    }

    /** {@code /quiz skip}: reveal the current answer and ask the next question. */
    public void skip(CommandSender sender) {
        if (!plugin.getConfig().getBoolean("pub.quiz.enabled", true)) {
            sender.sendMessage(prefix().append(Component.text("The pub quiz is closed.", NamedTextColor.RED)));
            return;
        }
        QuizQuestion question = active.getAndSet(null);
        if (question != null) {
            Bukkit.broadcast(prefix().append(Component.text(
                    "Question skipped. The answer was " + question.displayAnswer() + ".",
                    NamedTextColor.GRAY)));
        }
        lastFinishedAt = System.currentTimeMillis();
        if (questions.isEmpty()) {
            sender.sendMessage(prefix().append(Component.text("No quiz questions are configured.",
                    NamedTextColor.RED)));
            return;
        }
        ask();
    }

    /** {@code /quiz status}: report the live question or the countdown to the next one. */
    public void status(CommandSender sender) {
        long now = System.currentTimeMillis();
        QuizQuestion question = active.get();
        if (question != null) {
            int timeout = timeoutSeconds();
            long remaining = Math.max(0L, startedAt + timeout * 1000L - now) / 1000L;
            sender.sendMessage(prefix().append(Component.text(
                    "Running: " + question.question() + " (" + remaining + "s left).",
                    NamedTextColor.WHITE)));
            sender.sendMessage(prefix().append(Component.text(
                    "Accepted answer: " + String.join(" / ", question.answers()), NamedTextColor.GRAY)));
            return;
        }
        if (!plugin.getConfig().getBoolean("pub.quiz.enabled", true)) {
            sender.sendMessage(prefix().append(Component.text("The pub quiz is closed.",
                    NamedTextColor.RED)));
            return;
        }
        long due = lastFinishedAt + intervalSeconds() * 1000L;
        long remaining = Math.max(0L, due - now) / 1000L;
        sender.sendMessage(prefix().append(Component.text(
                "No question running. Next one in " + remaining + "s (" + questions.size()
                        + " questions loaded).", NamedTextColor.GRAY)));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        QuizQuestion question = active.get();
        if (question != null) {
            if (now - startedAt > timeoutSeconds() * 1000L && active.compareAndSet(question, null)) {
                lastFinishedAt = now;
                Bukkit.broadcast(prefix().append(Component.text(
                        "Time! The answer was " + question.displayAnswer() + ".", NamedTextColor.GRAY)));
            }
            return;
        }
        if (!plugin.getConfig().getBoolean("pub.quiz.enabled", true)) {
            return;
        }
        if (questions.isEmpty() || Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        long due = lastFinishedAt + intervalSeconds() * 1000L;
        if (now >= due) {
            // Never fire the 30s and 5s warnings in the same tick for an
            // already-overdue question; just ask it.
            ask();
            return;
        }
        if (now >= due - 5_000L) {
            if (!warnedFive) {
                warnedFive = true;
                Bukkit.broadcast(prefix().append(Component.text("5 seconds.", NamedTextColor.GOLD)));
                for (Player player : Bukkit.getOnlinePlayers()) {
                    GuabSounds.play(player, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 0.7f, 1.0f);
                }
            }
        } else if (now >= due - 30_000L && !warnedThirty) {
            warnedThirty = true;
            Bukkit.broadcast(prefix().append(Component.text(
                    "First correct answer wins " + reward() + " points in 30 seconds.",
                    NamedTextColor.GOLD)));
        }
    }

    private void ask() {
        warnedThirty = false;
        warnedFive = false;
        QuizQuestion question = pick();
        if (question == null) {
            return;
        }
        active.set(question);
        startedAt = System.currentTimeMillis();
        lastQuestion = question.question();
        Bukkit.broadcast(prefix()
                .append(Component.text("Question: ", NamedTextColor.GOLD))
                .append(Component.text(question.question(), NamedTextColor.WHITE))
                .append(Component.text("  (", NamedTextColor.DARK_GRAY))
                .append(Component.text("first correct answer wins " + reward() + " points",
                        NamedTextColor.GRAY))
                .append(Component.text(")", NamedTextColor.DARK_GRAY)));
    }

    private QuizQuestion pick() {
        if (questions.isEmpty()) {
            return null;
        }
        if (questions.size() == 1) {
            return questions.get(0);
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            QuizQuestion candidate = questions.get(ThreadLocalRandom.current().nextInt(questions.size()));
            if (!candidate.question().equals(lastQuestion)) {
                return candidate;
            }
        }
        return questions.get(ThreadLocalRandom.current().nextInt(questions.size()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        QuizQuestion question = active.get();
        if (question == null) {
            return;
        }
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (message.isEmpty() || message.startsWith("/") || !question.matches(message)) {
            return;
        }
        if (!active.compareAndSet(question, null)) {
            return;
        }
        lastFinishedAt = System.currentTimeMillis();
        Player winner = event.getPlayer();
        int reward = reward();

        // The chat event is async; award on the main thread.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            PointsService points = points();
            if (points != null) {
                points.add(winner.getUniqueId(), reward);
            }
            if (winner.isOnline()) {
                GuabSounds.play(winner, GuabSounds.QUIZ_CORRECT, 1.0f, 1.0f);
            }
            Bukkit.broadcast(prefix()
                    .append(Component.text(winner.getName(), NamedTextColor.WHITE))
                    .append(Component.text(" got it — ", NamedTextColor.GRAY))
                    .append(Component.text(question.displayAnswer(), NamedTextColor.GOLD))
                    .append(Component.text("  (+" + reward + " points)", NamedTextColor.GRAY)));
        });
    }

    private int reward() {
        int reward = Math.max(1, plugin.getConfig().getInt("pub.quiz.reward", 100));
        PubManager pub = plugin.pub();
        if (pub != null && pub.happyHourActive()) {
            reward *= Math.max(1,
                    plugin.getConfig().getInt("pub.happy-hour.quiz-reward-multiplier", 2));
        }
        return reward;
    }

    private int intervalSeconds() {
        return Math.max(60, plugin.getConfig().getInt("pub.quiz.interval-seconds", 600));
    }

    private int timeoutSeconds() {
        return Math.max(10, plugin.getConfig().getInt("pub.quiz.timeout-seconds", 90));
    }

    private void loadQuestions() {
        questions.clear();
        for (String raw : plugin.getConfig().getStringList("pub.quiz.questions")) {
            QuizQuestion parsed = QuizQuestion.parse(raw);
            if (parsed != null) {
                questions.add(parsed);
            }
        }
    }

    private PointsService points() {
        var registration = Bukkit.getServicesManager().getRegistration(PointsService.class);
        return registration == null ? null : registration.getProvider();
    }

    private Component prefix() {
        return Component.text("[Pub Quiz] ", NamedTextColor.GOLD);
    }
}
