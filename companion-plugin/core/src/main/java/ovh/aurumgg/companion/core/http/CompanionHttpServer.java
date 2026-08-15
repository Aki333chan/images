package ovh.aurumgg.companion.core.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import ovh.aurumgg.companion.core.CompanionConfig;
import ovh.aurumgg.companion.core.GameBridge;
import ovh.aurumgg.companion.core.json.JsonParser;
import ovh.aurumgg.companion.core.json.PayloadWriter;
import ovh.aurumgg.companion.core.model.InventoryInfo;
import ovh.aurumgg.companion.core.model.ItemSpec;
import ovh.aurumgg.companion.core.model.PlayerInfo;

/**
 * Входящий HTTP-сервер плагина (панель → плагин).
 *
 * Реализован на встроенном в JDK com.sun.net.httpserver, чтобы не тащить
 * в плагин веб-фреймворк. Все обращения к игре идут через GameBridge, который
 * сам переносит их в основной поток сервера.
 *
 * Маршруты:
 *   GET  /players
 *   GET  /players/{uuid}/inventory
 *   POST /players/{uuid}/inventory/{slot}
 */
public final class CompanionHttpServer {

    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final CompanionConfig config;
    private final GameBridge bridge;
    private final TokenAuth auth;
    private final Consumer<String> logger;

    private HttpServer server;
    private ExecutorService executor;

    public CompanionHttpServer(CompanionConfig config, GameBridge bridge, Consumer<String> logger) {
        this.config = config;
        this.bridge = bridge;
        this.auth = new TokenAuth(config.token());
        this.logger = logger;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
        // Двух потоков достаточно: запросы редкие, а тяжёлая работа всё равно
        // выполняется в основном потоке игры.
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "aurum-companion-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    /** Отдан для тестов: реальный порт, если в конфиге стоял 0. */
    public int boundPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!auth.isAuthorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                // Ни адреса, ни токена в логах — только факт отказа.
                logger.accept("Отклонён запрос без корректного токена: " + exchange.getRequestURI().getPath());
                respond(exchange, 401, PayloadWriter.error("Неверный токен"));
                return;
            }
            route(exchange);
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, PayloadWriter.error(e.getMessage()));
        } catch (Exception e) {
            logger.accept("Ошибка обработки запроса: " + e);
            respond(exchange, 500, PayloadWriter.error("Внутренняя ошибка плагина"));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String[] parts = splitPath(exchange.getRequestURI().getPath());

        // GET /players
        if (parts.length == 1 && parts[0].equals("players") && method.equals("GET")) {
            List<PlayerInfo> players = bridge.onlinePlayers();
            respond(exchange, 200, PayloadWriter.players(players));
            return;
        }

        // GET /players/{uuid}/inventory
        if (parts.length == 3
                && parts[0].equals("players")
                && parts[2].equals("inventory")
                && method.equals("GET")) {
            UUID uuid = parseUuid(parts[1]);
            Optional<InventoryInfo> inventory = bridge.inventory(uuid);
            if (inventory.isEmpty()) {
                respond(exchange, 404, PayloadWriter.error("Игрок не в сети"));
                return;
            }
            respond(exchange, 200, PayloadWriter.inventory(inventory.get()));
            return;
        }

        // POST /players/{uuid}/inventory/{slot}
        if (parts.length == 4
                && parts[0].equals("players")
                && parts[2].equals("inventory")
                && method.equals("POST")) {
            UUID uuid = parseUuid(parts[1]);
            int slot = parseSlot(parts[3]);
            ItemSpec spec = parseItemSpec(readBody(exchange));
            boolean applied = bridge.setInventorySlot(uuid, slot, spec);
            if (!applied) {
                respond(exchange, 404, PayloadWriter.error("Игрок не в сети или неизвестный предмет"));
                return;
            }
            respond(exchange, 200, PayloadWriter.ok());
            return;
        }

        respond(exchange, 404, PayloadWriter.error("Неизвестный маршрут"));
    }

    private static String[] splitPath(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.isEmpty()) return new String[0];
        return trimmed.split("/");
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный UUID игрока");
        }
    }

    private static int parseSlot(String raw) {
        int slot;
        try {
            slot = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Некорректный номер слота");
        }
        if (slot < 0 || slot > 35) {
            throw new IllegalArgumentException("Слот должен быть в диапазоне 0-35");
        }
        return slot;
    }

    /**
     * Тело: {"id":"minecraft:stone","count":3} — положить предмет,
     * {} либо {"id":null} — очистить слот.
     */
    static ItemSpec parseItemSpec(String body) {
        if (body == null || body.isBlank()) return ItemSpec.clear();
        Map<String, Object> parsed = JsonParser.parseObject(body);
        Object id = parsed.get("id");
        if (id == null) return ItemSpec.clear();
        if (!(id instanceof String idString) || idString.isBlank()) {
            throw new IllegalArgumentException("Поле id должно быть строкой");
        }
        Object rawCount = parsed.getOrDefault("count", 1.0d);
        if (!(rawCount instanceof Double count)) {
            throw new IllegalArgumentException("Поле count должно быть числом");
        }
        int amount = (int) Math.round(count);
        if (amount <= 0) return ItemSpec.clear();
        if (amount > 64) throw new IllegalArgumentException("count не может превышать 64");
        return new ItemSpec(idString, amount);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
    }
}
