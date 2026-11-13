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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A tiny token-protected HTTP endpoint that executes Minecraft commands with
 * {@code curl}. Bind it to loopback and use an SSH tunnel.
 *
 * <p>GET /command?cmd=list with {@code Authorization: Bearer ...} (or the
 * legacy {@code token} query parameter).
 */
public final class GuabWebConsolePlugin extends JavaPlugin {

    private static final String DEFAULT_TOKEN = "change-me-to-a-long-random-string";
    private static final String DEFAULT_BIND = "127.0.0.1";
    private static final int DEFAULT_MAX_BODY_BYTES = 16 * 1024;

    private HttpServer server;
    private ExecutorService executor;
    private String token;
    private String bind;
    private int port;
    private int timeoutSeconds;
    private int maxCommandLength;
    private int maxBodyBytes;
    private int rateLimitPerMinute;
    private List<String> allowedIps;
    private List<String> blockedPrefixes;
    private final Map<String, Deque<Long>> requests = new ConcurrentHashMap<>();

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
        this.bind = getConfig().getString("web.bind", DEFAULT_BIND);
        if (bind == null || bind.isBlank()) {
            bind = DEFAULT_BIND;
        }
        this.port = getConfig().getInt("web.port", 39100);
        this.timeoutSeconds = Math.max(1, getConfig().getInt("web.request-timeout-seconds", 10));
        this.maxCommandLength = Math.max(32, getConfig().getInt("web.max-command-length", 1000));
        this.maxBodyBytes = Math.max(1024, getConfig().getInt("web.max-body-bytes", DEFAULT_MAX_BODY_BYTES));
        this.rateLimitPerMinute = Math.max(1, getConfig().getInt("web.rate-limit-per-minute", 60));
        this.allowedIps = getConfig().getStringList("web.allowed-ips");
        this.blockedPrefixes = getConfig().getStringList("web.blocked-prefixes").stream()
                .map(prefix -> prefix.toLowerCase(Locale.ROOT))
                .toList();

        if (!DEFAULT_BIND.equals(bind) && !"localhost".equalsIgnoreCase(bind)) {
            getLogger().warning("Web console is bound to " + bind
                    + " (not loopback). Use an SSH tunnel or a reverse proxy with TLS; "
                    + "plain HTTP tokens are sniffable.");
        }

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
        getLogger().info("Web console listening on " + bind + ":" + port
                + " (Bearer token; loopback + SSH tunnel recommended).");
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

            String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!allowedIps.isEmpty() && !allowedIps.contains(remote)) {
                respond(exchange, 403, "forbidden\n");
                return;
            }
            // Count unauthenticated attempts too, so a token-guessing loop is throttled before any body is read.
            if (rateLimited(remote)) {
                respond(exchange, 429, "rate limited\n");
                return;
            }

            Map<String, String> params = new LinkedHashMap<>(parseQuery(exchange.getRequestURI().getRawQuery()));
            String provided = headerToken(exchange);
            if (provided == null) {
                provided = params.remove("token");
            }
            if (!matches(provided)) {
                getLogger().warning("Rejected web command from " + remote + ": bad token.");
                respond(exchange, 401, "bad token\n");
                return;
            }

            if (method.equals("POST")) {
                // Only read the body after authentication and the IP/rate checks pass.
                params.putAll(parseBody(exchange));
            }

            String command = params.get("cmd");
            if (command == null || command.isBlank()) {
                respond(exchange, 400, "missing cmd\n");
                return;
            }
            command = normalizeCommand(command);
            if (command.isEmpty()) {
                respond(exchange, 400, "missing cmd\n");
                return;
            }
            if (command.length() > maxCommandLength) {
                respond(exchange, 413, "command too long\n");
                return;
            }
            String lower = command.toLowerCase(Locale.ROOT);
            String namespaceless = stripNamespace(lower);
            for (String prefix : blockedPrefixes) {
                if (!prefix.isBlank()
                        && (lower.startsWith(prefix) || namespaceless.startsWith(prefix))) {
                    respond(exchange, 403, "command blocked\n");
                    return;
                }
            }

            List<String> output = run(command);
            getLogger().info("[" + remote + "] /" + command);
            respond(exchange, 200, output.isEmpty() ? "OK\n" : String.join("\n", output) + "\n");
        } catch (BodyTooLargeException exception) {
            respond(exchange, 413, "body too large\n");
        } catch (Exception exception) {
            getLogger().warning("Web command failed: " + exception);
            respond(exchange, 500, "error: " + exception.getMessage() + "\n");
        } finally {
            exchange.close();
        }
    }

    private String headerToken(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null && authorization.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            String value = authorization.substring(7).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        String direct = exchange.getRequestHeaders().getFirst("X-Guab-Token");
        if (direct != null && !direct.isBlank()) {
            return direct.trim();
        }
        String alternate = exchange.getRequestHeaders().getFirst("X-Auth-Token");
        if (alternate != null && !alternate.isBlank()) {
            return alternate.trim();
        }
        return null;
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

    /**
     * Strips every leading slash and trims again, so {@code "/  op player"}
     * cannot hide its leading spaces from the blocked-prefix check.
     */
    private static String normalizeCommand(String raw) {
        String command = raw == null ? "" : raw.trim();
        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        return command;
    }

    /** Removes a leading {@code minecraft:} (or any other) namespace for the block list. */
    private static String stripNamespace(String command) {
        int colon = command.indexOf(':');
        if (colon <= 0 || command.indexOf(' ') != -1 && colon > command.indexOf(' ')) {
            return command;
        }
        return command.substring(colon + 1).trim();
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
        }
        // Periodically drop idle addresses so the map cannot grow forever.
        if (requests.size() > 256) {
            requests.entrySet().removeIf(entry -> {
                Deque<Long> value = entry.getValue();
                synchronized (value) {
                    return value.isEmpty() || now - value.peekLast() > 60_000L;
                }
            });
        }
        return false;
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
        long declared = parseContentLength(exchange);
        if (declared > maxBodyBytes) {
            throw new BodyTooLargeException();
        }
        try (InputStream stream = exchange.getRequestBody()) {
            byte[] data = readLimited(stream);
            String body = new String(data, StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return Map.of();
            }
            if (contentType != null
                    && contentType.toLowerCase(Locale.ROOT).contains("application/x-www-form-urlencoded")) {
                Map<String, String> form = parseQuery(body);
                if (form.containsKey("cmd")) {
                    return form;
                }
                // curl --data sends form encoding; treat a bare body as the command.
            }
            return Map.of("cmd", body.trim());
        }
    }

    private long parseContentLength(HttpExchange exchange) {
        String raw = exchange.getRequestHeaders().getFirst("Content-Length");
        if (raw == null || raw.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private byte[] readLimited(InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total += read;
            if (total > maxBodyBytes) {
                throw new BodyTooLargeException();
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(bytes);
            }
        } catch (IOException ignored) {
            // The client disconnected; nothing left to do.
        }
    }

    private static final class BodyTooLargeException extends IOException {
        private BodyTooLargeException() {
            super("body too large");
        }
    }
}
