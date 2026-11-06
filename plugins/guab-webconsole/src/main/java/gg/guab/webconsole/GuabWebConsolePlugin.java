package gg.guab.webconsole;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A tiny token-protected HTTP endpoint that executes Minecraft commands with
 * {@code curl}. Intended for hosts that expose an extra TCP port.
 *
 * <p>GET /command?token=...&amp;cmd=list
 */
public final class GuabWebConsolePlugin extends JavaPlugin {

    private static final String DEFAULT_TOKEN = "change-me-to-a-long-random-string";

    private HttpServer server;
    private ExecutorService executor;
    private String token;
    private String bind;
    private int port;
    private int timeoutSeconds;
    private int maxCommandLength;
    private int rateLimitPerMinute;
    private List<String> allowedIps;
    private List<String> blockedPrefixes;
    private final Map<String, Deque<Long>> requests = Collections.synchronizedMap(new LinkedHashMap<>());

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!getConfig().getBoolean("web.enabled", true)) {
            getLogger().info("Web console disabled in config.");
            return;
        }
        this.token = getConfig().getString("web.token", "");
        if (token == null || token.isBlank() || token.equals(DEFAULT_TOKEN)) {
            getLogger().severe("Web console not started: set a long random web.token in config.yml.");
            return;
        }
        this.bind = getConfig().getString("web.bind", "0.0.0.0");
        this.port = getConfig().getInt("web.port", 39100);
        this.timeoutSeconds = Math.max(1, getConfig().getInt("web.request-timeout-seconds", 10));
        this.maxCommandLength = Math.max(32, getConfig().getInt("web.max-command-length", 1000));
        this.rateLimitPerMinute = Math.max(1, getConfig().getInt("web.rate-limit-per-minute", 60));
        this.allowedIps = getConfig().getStringList("web.allowed-ips");
        this.blockedPrefixes = getConfig().getStringList("web.blocked-prefixes").stream()
                .map(prefix -> prefix.toLowerCase(Locale.ROOT))
                .toList();

        try {
            this.server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        } catch (IOException | RuntimeException exception) {
            getLogger().severe("Could not bind web console on " + bind + ":" + port + " — " + exception.getMessage());
            return;
        }
        this.executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "GuabWebConsole");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/health", this::health);
        server.createContext("/command", this::command);
        server.start();
        getLogger().info("Web console listening on " + bind + ":" + port + " (POST/GET /command?token=...&cmd=...).");
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void health(HttpExchange exchange) {
        respond(exchange, 200, "ok\n");
    }

    private void command(HttpExchange exchange) {
        try {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!method.equals("GET") && !method.equals("POST")) {
                respond(exchange, 405, "method not allowed\n");
                return;
            }
            Map<String, String> params = new LinkedHashMap<>(parseQuery(exchange.getRequestURI().getRawQuery()));
            if (method.equals("POST")) {
                params.putAll(parseBody(exchange));
            }

            String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!allowedIps.isEmpty() && !allowedIps.contains(remote)) {
                respond(exchange, 403, "forbidden\n");
                return;
            }
            String provided = params.get("token");
            if (provided == null) {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (authorization != null && authorization.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                    provided = authorization.substring(7).trim();
                }
            }
            if (!matches(provided)) {
                getLogger().warning("Rejected web command from " + remote + ": bad token.");
                respond(exchange, 401, "bad token\n");
                return;
            }
            if (rateLimited(remote)) {
                respond(exchange, 429, "rate limited\n");
                return;
            }

            String command = params.get("cmd");
            if (command == null || command.isBlank()) {
                respond(exchange, 400, "missing cmd\n");
                return;
            }
            command = command.trim();
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            if (command.length() > maxCommandLength) {
                respond(exchange, 413, "command too long\n");
                return;
            }
            String lower = command.toLowerCase(Locale.ROOT);
            for (String prefix : blockedPrefixes) {
                if (!prefix.isBlank() && lower.startsWith(prefix)) {
                    respond(exchange, 403, "command blocked\n");
                    return;
                }
            }

            List<String> output = run(command);
            getLogger().info("[" + remote + "] /" + command);
            respond(exchange, 200, output.isEmpty() ? "OK\n" : String.join("\n", output) + "\n");
        } catch (Exception exception) {
            getLogger().warning("Web command failed: " + exception);
            respond(exchange, 500, "error: " + exception.getMessage() + "\n");
        } finally {
            exchange.close();
        }
    }

    private List<String> run(String command) throws Exception {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> {
            List<String> lines = new ArrayList<>();
            try {
                CommandSender sender = Bukkit.createCommandSender(component ->
                        lines.add(LegacyComponentSerializer.legacySection().serialize(component)));
                Bukkit.dispatchCommand(sender, command);
                future.complete(lines);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            return List.of("timed out on the server thread");
        }
    }

    private boolean matches(String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private boolean rateLimited(String remote) {
        long now = System.currentTimeMillis();
        Deque<Long> hits = requests.computeIfAbsent(remote, key -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() > 60_000L) {
                hits.pollFirst();
            }
            if (hits.size() >= rateLimitPerMinute) {
                return true;
            }
            hits.addLast(now);
            return false;
        }
    }

    private Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int split = pair.indexOf('=');
            if (split < 0) {
                continue;
            }
            out.put(decode(pair.substring(0, split)), decode(pair.substring(split + 1)));
        }
        return out;
    }

    private Map<String, String> parseBody(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        try (InputStream stream = exchange.getRequestBody()) {
            String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return Map.of();
            }
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("application/x-www-form-urlencoded")) {
                return parseQuery(body);
            }
            return Map.of("cmd", body.trim());
        }
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(bytes);
            }
        } catch (IOException ignored) {
            // The client disconnected; nothing left to do.
        }
    }
}
