package gg.guab.items;

import gg.guab.core.PointsService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The pub quiz. A question goes out in chat; the first correct answer wins points.
 */
public final class PubQuiz implements Listener {

    private final GuabItemsPlugin plugin;
    private final List<Question> questions = new ArrayList<>();
    private BukkitTask task;
    private Question active;
    private long startedAt;
    private long lastFinishedAt;
    private String lastQuestion;

    public PubQuiz(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        loadQuestions();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        lastFinishedAt = System.currentTimeMillis();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        active = null;
    }

    public boolean isActive() {
        return active != null;
    }

    public void begin(CommandSender sender) {
        if (!plugin.getConfig().getBoolean("pub.quiz.enabled", true)) {
            sender.sendMessage(prefix().append(Component.text("The pub quiz is closed.", NamedTextColor.RED)));
            return;
        }
        if (active != null) {
            sender.sendMessage(prefix().append(Component.text("A question is already running.", NamedTextColor.YELLOW)));
            return;
        }
        if (questions.isEmpty()) {
            sender.sendMessage(prefix().append(Component.text("No quiz questions are configured.", NamedTextColor.RED)));
            return;
        }
        ask();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (active != null) {
            int timeout = Math.max(10, plugin.getConfig().getInt("pub.quiz.timeout-seconds", 90));
            if (now - startedAt > timeout * 1000L) {
                Bukkit.broadcast(prefix().append(Component.text(
                        "Time! The answer was " + active.displayAnswer() + ".", NamedTextColor.GRAY)));
                active = null;
                lastFinishedAt = now;
            }
            return;
        }
        if (!plugin.getConfig().getBoolean("pub.quiz.enabled", true)) {
            return;
        }
        int interval = Math.max(60, plugin.getConfig().getInt("pub.quiz.interval-seconds", 600));
        if (now - lastFinishedAt >= interval * 1000L && !Bukkit.getOnlinePlayers().isEmpty()) {
            ask();
        }
    }

    private void ask() {
        Question question = pick();
        if (question == null) {
            return;
        }
        active = question;
        startedAt = System.currentTimeMillis();
        lastQuestion = question.question;
        Bukkit.broadcast(prefix()
                .append(Component.text("Question: ", NamedTextColor.GOLD))
                .append(Component.text(question.question, NamedTextColor.WHITE))
                .append(Component.text("  (", NamedTextColor.DARK_GRAY))
                .append(Component.text("first correct answer wins " + reward() + " points", NamedTextColor.GRAY))
                .append(Component.text(")", NamedTextColor.DARK_GRAY)));
    }

    private Question pick() {
        if (questions.size() == 1) {
            return questions.get(0);
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            Question candidate = questions.get(ThreadLocalRandom.current().nextInt(questions.size()));
            if (!candidate.question.equals(lastQuestion)) {
                return candidate;
            }
        }
        return questions.get(ThreadLocalRandom.current().nextInt(questions.size()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Question question = active;
        if (question == null) {
            return;
        }
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (message.isEmpty() || message.startsWith("/") || !question.matches(message)) {
            return;
        }
        active = null;
        lastFinishedAt = System.currentTimeMillis();
        Player winner = event.getPlayer();
        int reward = reward();

        // The chat event is async; award on the main thread.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            PointsService points = points();
            if (points != null) {
                points.add(winner.getUniqueId(), reward);
            }
            Bukkit.broadcast(prefix()
                    .append(Component.text(winner.getName(), NamedTextColor.WHITE))
                    .append(Component.text(" got it — ", NamedTextColor.GRAY))
                    .append(Component.text(question.displayAnswer(), NamedTextColor.GOLD))
                    .append(Component.text("  (+" + reward + " points)", NamedTextColor.GRAY)));
        });
    }

    private int reward() {
        return Math.max(1, plugin.getConfig().getInt("pub.quiz.reward", 100));
    }

    private void loadQuestions() {
        questions.clear();
        for (String raw : plugin.getConfig().getStringList("pub.quiz.questions")) {
            int split = raw.indexOf('|');
            if (split < 1) {
                continue;
            }
            String question = raw.substring(0, split).trim();
            List<String> answers = new ArrayList<>();
            for (String answer : raw.substring(split + 1).split("/")) {
                String cleaned = answer.trim().toLowerCase(Locale.ROOT);
                if (!cleaned.isEmpty()) {
                    answers.add(cleaned);
                }
            }
            if (!question.isEmpty() && !answers.isEmpty()) {
                questions.add(new Question(question, List.copyOf(answers)));
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

    private record Question(String question, List<String> answers) {
        private boolean matches(String message) {
            String normalised = message.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
            return answers.contains(normalised);
        }

        private String displayAnswer() {
            return answers.get(0);
        }
    }
}
