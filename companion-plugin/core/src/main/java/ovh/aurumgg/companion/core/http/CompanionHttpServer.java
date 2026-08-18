package ovh.aurumgg.companion.core.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
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
import ovh.aurumgg.companion.core.model.BalanceChange;
import ovh.aurumgg.companion.core.model.BalanceInfo;
import ovh.aurumgg.companion.core.model.EconomySummary;
import ovh.aurumgg.companion.core.model.InventoryInfo;
import ovh.aurumgg.companion.core.model.ItemSpec;
import ovh.aurumgg.companion.core.model.PermissionChange;
import ovh.aurumgg.companion.core.model.PermissionsInfo;
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
 *   GET  /players/{uuid}/permissions
 *   POST /players/{uuid}/permissions
 *   GET  /plugins
 *   GET  /complete?line=...
 *   GET  /players/{uuid}/balance
 *   POST /players/{uuid}/balance/deposit
 *   POST /players/{uuid}/balance/withdraw
 *   GET  /economy?top=...
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

        // GET /plugins — что вообще установлено на этом сервере
        if (parts.length == 1 && parts[0].equals("plugins") && method.equals("GET")) {
            respond(exchange, 200, PayloadWriter.plugins(bridge.installedPlugins()));
            return;
        }

        // GET /complete?line=gamemo — автодополнение силами самого сервера
        if (parts.length == 1 && parts[0].equals("complete") && method.equals("GET")) {
            String line = normalizeCompletionLine(queryParam(exchange, "line"));
            List<String> suggestions = bridge.completeCommand(line);
            respond(exchange, 200, PayloadWriter.suggestions(limit(suggestions, MAX_SUGGESTIONS)));
            return;
        }

        // GET /players/{uuid}/inventory[?name=Steve]
        //
        // Сначала пробуем живой инвентарь через Paper. Если игрока нет в сети,
        // пробуем InvSee++ — и только если и его нет, отвечаем отказом с кодом,
        // по которому панель напишет, чего именно не хватает.
        if (parts.length == 3
                && parts[0].equals("players")
                && parts[2].equals("inventory")
                && method.equals("GET")) {
            UUID uuid = parseUuid(parts[1]);
            Optional<InventoryInfo> inventory = bridge.inventory(uuid);
            if (inventory.isPresent()) {
                respond(exchange, 200, PayloadWriter.inventory(inventory.get()));
                return;
            }

            String name = queryParam(exchange, "name");
            Optional<InventoryInfo> offline = bridge.offlineInventory(uuid, name);
            if (offline.isPresent()) {
                respond(exchange, 200, PayloadWriter.inventory(offline.get()));
                return;
            }

            boolean invseeInstalled = hasPlugin(INVSEE_PLUGIN);
            if (invseeInstalled) {
                respond(exchange, 404, PayloadWriter.error(
                        "Игрок не в сети, и InvSee++ не нашёл сохранённых данных о нём",
                        "offline-no-data"));
            } else {
                respond(exchange, 404, PayloadWriter.error(
                        "Игрок не в сети. Для офлайн-инвентарей нужен плагин InvSee++",
                        "offline-requires-invsee"));
            }
            return;
        }

        // GET /economy?top=10 — общий объём денег на сервере и доска богатства.
        // Считается по всем, кто когда-либо заходил, поэтому обращение дорогое;
        // кэширует результат панель, а не плагин: срок жизни кэша — её решение.
        if (parts.length == 1 && parts[0].equals("economy") && method.equals("GET")) {
            Optional<EconomySummary> summary = bridge.economySummary(parseTopLimit(queryParam(exchange, "top")));
            if (summary.isEmpty()) {
                respondNoEconomy(exchange);
                return;
            }
            respond(exchange, 200, PayloadWriter.economy(summary.get()));
            return;
        }

        // GET /players/{uuid}/balance — работает и для тех, кого нет в сети
        if (parts.length == 3
                && parts[0].equals("players")
                && parts[2].equals("balance")
                && method.equals("GET")) {
            UUID uuid = parseUuid(parts[1]);
            Optional<BalanceInfo> balance = bridge.balance(uuid);
            if (balance.isEmpty()) {
                respondNoEconomy(exchange);
                return;
            }
            respond(exchange, 200, PayloadWriter.balance(balance.get()));
            return;
        }

        // POST /players/{uuid}/balance/{deposit|withdraw} — тело {"amount":100}
        //
        // Поле reason панель присылает для журнала аудита, и оно здесь
        // намеренно игнорируется: журнал ведёт панель, у которой есть автор
        // операции. Плагину знать причину незачем, а писать её в лог сервера
        // значит дублировать запись без пользы.
        if (parts.length == 4
                && parts[0].equals("players")
                && parts[2].equals("balance")
                && method.equals("POST")
                && (parts[3].equals("deposit") || parts[3].equals("withdraw"))) {
            UUID uuid = parseUuid(parts[1]);
            double amount = parseAmount(readBody(exchange));
            boolean deposit = parts[3].equals("deposit");
            Optional<BalanceChange> change = deposit ? bridge.deposit(uuid, amount) : bridge.withdraw(uuid, amount);
            if (change.isEmpty()) {
                respondNoEconomy(exchange);
                return;
            }
            // Отказ провайдера («не хватает денег») — 200 с ok:false: запрос
            // корректен, а причина отказа нужна панели целиком, вместе с
            // балансом до и после, чтобы записать её в аудит.
            respond(exchange, 200, PayloadWriter.balanceChange(change.get()));
            return;
        }

        // GET /players/{uuid}/permissions — через LuckPerms
        if (parts.length == 3
                && parts[0].equals("players")
                && parts[2].equals("permissions")
                && method.equals("GET")) {
            UUID uuid = parseUuid(parts[1]);
            Optional<PermissionsInfo> permissions = bridge.permissions(uuid);
            if (permissions.isEmpty()) {
                respond(exchange, 404, PayloadWriter.error(
                        "Управление правами требует плагина LuckPerms", "requires-luckperms"));
                return;
            }
            respond(exchange, 200, PayloadWriter.permissions(permissions.get()));
            return;
        }

        // POST /players/{uuid}/permissions — одно изменение за запрос
        if (parts.length == 3
                && parts[0].equals("players")
                && parts[2].equals("permissions")
                && method.equals("POST")) {
            UUID uuid = parseUuid(parts[1]);
            PermissionChange change = parsePermissionChange(readBody(exchange));
            Optional<PermissionChange.Result> result = bridge.applyPermission(uuid, change);
            if (result.isEmpty()) {
                respond(exchange, 404, PayloadWriter.error(
                        "Управление правами требует плагина LuckPerms", "requires-luckperms"));
                return;
            }
            if (!result.get().applied()) {
                // 409, а не 400: запрос корректен, но состояние сервера не даёт
                // его выполнить — нет такой группы, право уже стоит и т.п.
                respond(exchange, 409, PayloadWriter.error(result.get().reason(), "rejected"));
                return;
            }
            // Возвращаем актуальное состояние: панели не нужен второй запрос,
            // и она не покажет то, что уже устарело.
            Optional<PermissionsInfo> updated = bridge.permissions(uuid);
            respond(exchange, 200, updated.map(PayloadWriter::permissions).orElseGet(PayloadWriter::ok));
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

    /** Имя InvSee++ в Bukkit — именно такое, «InvSee++» не зарегистрирован. */
    static final String INVSEE_PLUGIN = "InvSeePlusPlus";

    /** Имя Vault в Bukkit. */
    static final String VAULT_PLUGIN = "Vault";

    /** Сколько строк в доске богатства, если панель не попросила иного. */
    private static final int DEFAULT_TOP_LIMIT = 10;

    /** Верхняя граница: доска на тысячу строк никому не нужна, а ответ раздувает. */
    private static final int MAX_TOP_LIMIT = 100;

    /**
     * Сумма операции: строго положительное конечное число. Знак задаётся
     * маршрутом (deposit/withdraw), а не значением, — иначе «списать -100»
     * означало бы начисление, и в аудите это выглядело бы наоборот.
     */
    static double parseAmount(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Пустое тело запроса");
        }
        Object raw = JsonParser.parseObject(body).get("amount");
        if (!(raw instanceof Double amount)) {
            throw new IllegalArgumentException("Поле amount должно быть числом");
        }
        if (!Double.isFinite(amount) || amount <= 0) {
            throw new IllegalArgumentException("Поле amount должно быть больше нуля");
        }
        return amount;
    }

    static int parseTopLimit(String raw) {
        if (raw == null) return DEFAULT_TOP_LIMIT;
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Параметр top должен быть числом");
        }
        if (value < 0) throw new IllegalArgumentException("Параметр top не может быть отрицательным");
        return Math.min(value, MAX_TOP_LIMIT);
    }

    /**
     * Экономики нет — но причины две, и панели важно, какая именно: без Vault
     * надо ставить сам Vault, а с Vault без провайдера — плагин экономики.
     */
    private void respondNoEconomy(HttpExchange exchange) throws IOException {
        if (hasPlugin(VAULT_PLUGIN)) {
            respond(exchange, 404, PayloadWriter.error(
                    "Vault установлен, но плагин экономики не зарегистрировал провайдера", "no-provider"));
        } else {
            respond(exchange, 404, PayloadWriter.error(
                    "Работа с валютой требует плагина Vault и плагина экономики", "requires-vault"));
        }
    }

    /** Больше сотни вариантов в выпадающем списке всё равно бесполезны. */
    private static final int MAX_SUGGESTIONS = 100;

    /** Строка длиннее этого — заведомо не команда, а вставленный кусок текста. */
    private static final int MAX_COMPLETION_LINE = 256;

    /**
     * Приведение строки к тому виду, которого ждёт CommandMap#tabComplete:
     * без ведущего слэша (в консоли его не пишут, но из панели он мог приехать)
     * и разумной длины.
     */
    static String normalizeCompletionLine(String raw) {
        String line = raw == null ? "" : raw;
        while (line.startsWith("/")) line = line.substring(1);
        return line.length() > MAX_COMPLETION_LINE ? line.substring(0, MAX_COMPLETION_LINE) : line;
    }

    private static List<String> limit(List<String> values, int max) {
        if (values == null) return List.of();
        return values.size() <= max ? values : List.copyOf(values.subList(0, max));
    }

    private boolean hasPlugin(String name) {
        return bridge.installedPlugins().stream().anyMatch(p -> p.name().equalsIgnoreCase(name));
    }

    /** Значение параметра строки запроса или null. */
    private static String queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (!URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(key)) continue;
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            return value.isBlank() ? null : value;
        }
        return null;
    }

    /**
     * Тело: {"kind":"group","key":"vip","value":true,"remove":false}
     *
     * kind    — group либо permission
     * key     — имя группы или право вида essentials.fly
     * value   — знак ноды: true выдать, false явно запретить
     * remove  — снять ноду вместо добавления
     */
    static PermissionChange parsePermissionChange(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Пустое тело запроса");
        }
        Map<String, Object> parsed = JsonParser.parseObject(body);

        Object rawKind = parsed.get("kind");
        if (!(rawKind instanceof String kindString)) {
            throw new IllegalArgumentException("Поле kind должно быть строкой");
        }
        PermissionChange.Kind kind;
        switch (kindString) {
            case "group" -> kind = PermissionChange.Kind.GROUP;
            case "permission" -> kind = PermissionChange.Kind.PERMISSION;
            default -> throw new IllegalArgumentException("kind должен быть group или permission");
        }

        Object rawKey = parsed.get("key");
        if (!(rawKey instanceof String key) || key.isBlank()) {
            throw new IllegalArgumentException("Поле key обязательно");
        }
        // Ноды LuckPerms — ASCII без пробелов; проверяем здесь, чтобы кривое
        // значение не уехало в постоянное хранилище прав.
        if (!key.matches("[A-Za-z0-9_.*:\\-]{1,120}")) {
            throw new IllegalArgumentException(
                    "key может содержать только латиницу, цифры и символы . _ - * :");
        }

        boolean value = !Boolean.FALSE.equals(parsed.get("value"));
        boolean remove = Boolean.TRUE.equals(parsed.get("remove"));
        return new PermissionChange(kind, key, value, remove);
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
