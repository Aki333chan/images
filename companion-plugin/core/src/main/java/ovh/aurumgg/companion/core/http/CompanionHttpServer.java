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
import ovh.aurumgg.companion.core.model.GiveResult;
import ovh.aurumgg.companion.core.model.GuildActionOutcome;
import ovh.aurumgg.companion.core.model.GuildInfo;
import ovh.aurumgg.companion.core.model.GuildMembershipInfo;
import ovh.aurumgg.companion.core.model.InventoryInfo;
import ovh.aurumgg.companion.core.model.InventorySelection;
import ovh.aurumgg.companion.core.model.ItemSpec;
import ovh.aurumgg.companion.core.model.PermissionChange;
import ovh.aurumgg.companion.core.model.PasswordReset;
import ovh.aurumgg.companion.core.model.PermissionsInfo;
import ovh.aurumgg.companion.core.model.PlayerInfo;
import ovh.aurumgg.companion.core.model.PluginToggle;
import ovh.aurumgg.companion.core.webtoken.WebTokenStore;

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
 *   POST /players/{uuid}/inventory/give
 *   POST /players/{uuid}/inventory/clear
 *   GET  /players/{uuid}/permissions
 *   POST /players/{uuid}/permissions
 *   GET  /plugins
 *   POST /plugins/{name}/enabled
 *   GET  /complete?line=...
 *   GET  /players/{uuid}/balance
 *   POST /players/{uuid}/balance/deposit
 *   POST /players/{uuid}/balance/withdraw
 *   GET  /economy?top=...
 *   POST /webtoken/{code}
 *   POST /auth/reset/{name}
 */
public final class CompanionHttpServer {

    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final CompanionConfig config;
    private final GameBridge bridge;
    private final TokenAuth auth;
    private final Consumer<String> logger;
    private final WebTokenStore webTokens;

    private HttpServer server;
    private ExecutorService executor;

    public CompanionHttpServer(CompanionConfig config, GameBridge bridge, Consumer<String> logger) {
        this(config, bridge, logger, new WebTokenStore(java.time.Duration.ofMinutes(5)));
    }

    /**
     * Хранилище кодов приходит снаружи: коды выдаёт команда /webtoken в слое
     * Bukkit, а обменивает их панель через этот сервер — значит, это должен
     * быть один и тот же экземпляр.
     */
    public CompanionHttpServer(
            CompanionConfig config, GameBridge bridge, Consumer<String> logger, WebTokenStore webTokens) {
        this.config = config;
        this.bridge = bridge;
        this.auth = new TokenAuth(config.token());
        this.logger = logger;
        this.webTokens = webTokens;
    }

    /** То же хранилище, что использует сервер, — для команды /webtoken. */
    public WebTokenStore webTokens() {
        return webTokens;
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

        // POST /webtoken/{code} — панель обменивает код игрока на его личность.
        //
        // POST, а не GET, потому что запрос ИЗМЕНЯЕТ состояние: код
        // одноразовый и после обмена перестаёт работать. GET, который гасит
        // код, однажды сработал бы от превентивного запроса браузера или
        // повтора при ретрае.
        if (parts.length == 2 && parts[0].equals("webtoken") && method.equals("POST")) {
            Optional<WebTokenStore.Issued> issued =
                    webTokens.consume(decode(parts[1]), java.time.Instant.now());
            if (issued.isEmpty()) {
                // Один и тот же ответ на «не существует», «уже использован» и
                // «протух»: по разнице между ними подбор кода стал бы заметно
                // осмысленнее.
                respond(exchange, 404, PayloadWriter.error("Код не найден или истёк", "token-invalid"));
                return;
            }
            respond(exchange, 200,
                    PayloadWriter.webToken(issued.get().playerUuid(), issued.get().username()));
            return;
        }

        // POST /auth/reset/{name} — выдать игроку токен сброса пароля.
        //
        // Отдельная ветка /auth, а не /players/{uuid}/…: сброс нужен как раз
        // тем, кто войти не может, и UUID у панели зачастую нет — есть ник.
        if (parts.length == 3
                && parts[0].equals("auth")
                && parts[1].equals("reset")
                && method.equals("POST")) {
            Optional<PasswordReset> reset = bridge.issuePasswordReset(decode(parts[2]));
            if (reset.isEmpty()) {
                // Один ответ на «нет плагина авторизации» и «нет такого
                // аккаунта»: различать их значит помогать перебирать ники.
                respond(exchange, 404,
                        PayloadWriter.error("Сбросить пароль не удалось", "reset-unavailable"));
                return;
            }
            respond(exchange, 200, PayloadWriter.passwordReset(reset.get()));
            return;
        }

        // ---------------------------------------------------------- гильдии
        //
        // Раздел целиком отвечает 503, если плагина гильдий на сервере нет.
        // Именно 503, а не 404: маршрут существует и заработает, как только
        // плагин поставят, — а 404 панель истолковала бы как «такой гильдии
        // нет» и показала бы пустой список вместо объяснения.

        // GET /guilds?query=дра&limit=50
        if (parts.length == 1 && parts[0].equals("guilds") && method.equals("GET")) {
            if (guildsUnavailable(exchange)) return;
            List<GuildInfo> guilds =
                    bridge.guilds(queryParam(exchange, "query"), parseGuildLimit(queryParam(exchange, "limit")));
            respond(exchange, 200, PayloadWriter.guilds(guilds));
            return;
        }

        // GET /guilds/{id} — гильдия вместе с составом
        if (parts.length == 2 && parts[0].equals("guilds") && method.equals("GET")) {
            if (guildsUnavailable(exchange)) return;
            Optional<GuildInfo> guild = bridge.guild(parseGuildId(parts[1]));
            if (guild.isEmpty()) {
                respond(exchange, 404, PayloadWriter.error("Гильдия не найдена", "guild-not-found"));
                return;
            }
            respond(exchange, 200, PayloadWriter.guild(guild.get()));
            return;
        }

        // POST /guilds/{id}/disband — тело {"actor":"ГМ"}
        if (parts.length == 3
                && parts[0].equals("guilds")
                && parts[2].equals("disband")
                && method.equals("POST")) {
            if (guildsUnavailable(exchange)) return;
            respondOutcome(exchange,
                    bridge.guildDisband(parseGuildId(parts[1]), actorFrom(readBody(exchange))));
            return;
        }

        // POST /guilds/{id}/transfer — тело {"actor":"ГМ","target":"Стив"}
        if (parts.length == 3
                && parts[0].equals("guilds")
                && parts[2].equals("transfer")
                && method.equals("POST")) {
            if (guildsUnavailable(exchange)) return;
            Map<String, Object> body = JsonParser.parseObject(readBody(exchange));
            String target = stringField(body, "target");
            if (target.isBlank()) {
                respond(exchange, 400, PayloadWriter.error("Не указан игрок", "target-required"));
                return;
            }
            respondOutcome(exchange, bridge.guildTransfer(
                    parseGuildId(parts[1]), target, stringField(body, "actor")));
            return;
        }

        // POST /guilds/members/{ник}/remove — исключить игрока из его гильдии.
        //
        // По нику, а не по id гильдии: игрок состоит максимум в одной, и
        // заставлять панель сначала выяснять, в какой именно, значило бы
        // требовать лишний запрос ради того, что плагин гильдий знает и так.
        if (parts.length == 4
                && parts[0].equals("guilds")
                && parts[1].equals("members")
                && parts[3].equals("remove")
                && method.equals("POST")) {
            if (guildsUnavailable(exchange)) return;
            respondOutcome(exchange,
                    bridge.guildRemoveMember(decode(parts[2]), actorFrom(readBody(exchange))));
            return;
        }

        // GET /players/{uuid}/guild — для карточки игрока
        if (parts.length == 3
                && parts[0].equals("players")
                && parts[2].equals("guild")
                && method.equals("GET")) {
            if (guildsUnavailable(exchange)) return;
            Optional<GuildMembershipInfo> membership = bridge.guildOf(parseUuid(parts[1]));
            if (membership.isEmpty()) {
                // 200 с пустым телом, а не 404: «не состоит в гильдии» — это
                // обычное состояние игрока, а не отсутствие ресурса.
                respond(exchange, 200, "{\"membership\":null}");
                return;
            }
            respond(exchange, 200, PayloadWriter.guildMembership(membership.get()));
            return;
        }

        // GET /plugins — что вообще установлено на этом сервере
        if (parts.length == 1 && parts[0].equals("plugins") && method.equals("GET")) {
            respond(exchange, 200, PayloadWriter.plugins(bridge.installedPlugins()));
            return;
        }

        // POST /plugins/{name}/enabled — тело {"enabled":true|false}
        //
        // Горячее переключение без перезапуска. Best-effort по природе Bukkit:
        // формулировку про риск даёт панель, здесь — только результат.
        if (parts.length == 3
                && parts[0].equals("plugins")
                && parts[2].equals("enabled")
                && method.equals("POST")) {
            String name = decode(parts[1]);
            boolean enabled = parseEnabled(readBody(exchange));
            PluginToggle result = bridge.setPluginEnabled(name, enabled);
            if (!result.ok()) {
                // 409, а не 400: запрос корректен, отказало состояние сервера.
                respond(exchange, 409, PayloadWriter.error(result.error(), "toggle-failed"));
                return;
            }
            respond(exchange, 200, PayloadWriter.pluginToggle(result));
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

        // POST /players/{uuid}/inventory/give
        //
        // Раньше маршрута по номеру слота: у обоих одинаковая форма пути, и
        // разбор «give» как номера слота свалился бы в 400 вместо выдачи.
        if (parts.length == 4
                && parts[0].equals("players")
                && parts[2].equals("inventory")
                && parts[3].equals("give")
                && method.equals("POST")) {
            UUID uuid = parseUuid(parts[1]);
            List<ItemSpec> wanted = parseGiveList(readBody(exchange));
            Optional<List<GiveResult>> results = bridge.giveItems(uuid, wanted);
            if (results.isEmpty()) {
                respond(exchange, 404, PayloadWriter.error("Игрок не в сети", "player-offline"));
                return;
            }
            respond(exchange, 200, PayloadWriter.giveResults(results.get()));
            return;
        }

        // POST /players/{uuid}/inventory/clear
        if (parts.length == 4
                && parts[0].equals("players")
                && parts[2].equals("inventory")
                && parts[3].equals("clear")
                && method.equals("POST")) {
            UUID uuid = parseUuid(parts[1]);
            InventorySelection selection = parseSelection(readBody(exchange));
            if (!bridge.clearInventory(uuid, selection)) {
                respond(exchange, 404, PayloadWriter.error("Игрок не в сети", "player-offline"));
                return;
            }
            respond(exchange, 200, PayloadWriter.ok());
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

    /** Тело {"enabled":true}. Отсутствие поля — ошибка, а не «по умолчанию». */
    static boolean parseEnabled(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Пустое тело запроса");
        }
        Object raw = JsonParser.parseObject(body).get("enabled");
        if (!(raw instanceof Boolean enabled)) {
            throw new IllegalArgumentException("Поле enabled должно быть true или false");
        }
        return enabled;
    }

    /** Имя плагина в пути закодировано: в нём бывают пробелы и плюсы. */
    private static String decode(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
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
    /**
     * Ответить 503, если плагина гильдий нет.
     *
     * @return true, если ответ уже отправлен и обработку пора прекратить
     */
    private boolean guildsUnavailable(HttpExchange exchange) throws IOException {
        if (bridge.guildsAvailable()) return false;
        respond(exchange, 503,
                PayloadWriter.error("Плагин гильдий не установлен", "guilds-unavailable"));
        return true;
    }

    private void respondOutcome(HttpExchange exchange, Optional<GuildActionOutcome> outcome)
            throws IOException {
        if (outcome.isEmpty()) {
            respond(exchange, 503,
                    PayloadWriter.error("Плагин гильдий не установлен", "guilds-unavailable"));
            return;
        }
        // 409 при отказе: запрос корректен, отказало состояние игрового
        // сервера — например, такой гильдии уже нет.
        respond(exchange, outcome.get().ok() ? 200 : 409, PayloadWriter.guildOutcome(outcome.get()));
    }

    /** Кто выполняет действие. Пустое значение — не ошибка: в логе будет «панель». */
    private static String actorFrom(String body) {
        String actor = stringField(JsonParser.parseObject(body), "actor");
        return actor.isBlank() ? "панель" : actor;
    }

    private static String stringField(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    /** Негодный id — это «такой гильдии нет», а не ошибка разбора. */
    private static long parseGuildId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseGuildLimit(String raw) {
        if (raw == null || raw.isBlank()) return 100;
        try {
            return Math.max(1, Math.min(500, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return 100;
        }
    }

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

    /** Столько строк за раз хватает на любой разумный набор — в инвентаре 36 слотов. */
    static final int MAX_GIVE_ENTRIES = 45;

    /**
     * Больше одного инвентаря одной строкой выдать нельзя.
     *
     * 36 слотов по 64 — ровно столько влезает в основной инвентарь, и всё, что
     * сверх, всё равно вернулось бы обратно как не поместившееся. Верхняя
     * граница нужна не ради этого: без неё опечатка в поле count («64000000»)
     * заставит сервер собирать миллион ItemStack в основном потоке.
     */
    static final int MAX_GIVE_COUNT = 36 * 64;

    /**
     * Тело: {"items":[{"id":"minecraft:stone","count":64}, ...]}.
     *
     * Список, а не один предмет: выдают обычно набор, и отдельный запрос на
     * каждую строку означал бы частично применённую выдачу при обрыве связи
     * посередине.
     */
    static List<ItemSpec> parseGiveList(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Пустое тело запроса");
        }
        Object raw = JsonParser.parseObject(body).get("items");
        if (!(raw instanceof List<?> rawItems) || rawItems.isEmpty()) {
            throw new IllegalArgumentException("Поле items должно быть непустым списком");
        }
        if (rawItems.size() > MAX_GIVE_ENTRIES) {
            throw new IllegalArgumentException("Не больше " + MAX_GIVE_ENTRIES + " строк за раз");
        }
        List<ItemSpec> result = new java.util.ArrayList<>(rawItems.size());
        for (Object entry : rawItems) {
            if (!(entry instanceof Map<?, ?> fields)) {
                throw new IllegalArgumentException("Каждая строка items должна быть объектом");
            }
            Object id = fields.get("id");
            if (!(id instanceof String idString) || idString.isBlank()) {
                throw new IllegalArgumentException("Поле id должно быть непустой строкой");
            }
            Object rawCount = fields.get("count");
            if (rawCount == null) rawCount = 1.0d;
            if (!(rawCount instanceof Double count)) {
                throw new IllegalArgumentException("Поле count должно быть числом");
            }
            int amount = (int) Math.round(count);
            if (amount <= 0) {
                throw new IllegalArgumentException("Поле count должно быть больше нуля");
            }
            if (amount > MAX_GIVE_COUNT) {
                throw new IllegalArgumentException("count не может превышать " + MAX_GIVE_COUNT);
            }
            result.add(new ItemSpec(idString, amount));
        }
        return result;
    }

    /**
     * Тело: {"all":true} либо {"slots":[..],"armor":[..],"offhand":true}.
     *
     * Пустой выбор — ошибка запроса, а не «очистить всё»: разница здесь
     * необратимая, и потерянное по дороге поле не должно оборачиваться
     * стёртым инвентарём.
     */
    static InventorySelection parseSelection(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Пустое тело запроса");
        }
        Map<String, Object> parsed = JsonParser.parseObject(body);
        if (Boolean.TRUE.equals(parsed.get("all"))) return InventorySelection.everything();

        List<Integer> slots = parseIndexList(parsed.get("slots"), 35, "slots");
        List<Integer> armor = parseIndexList(parsed.get("armor"), 3, "armor");
        boolean offhand = Boolean.TRUE.equals(parsed.get("offhand"));

        InventorySelection selection = new InventorySelection(false, slots, armor, offhand);
        if (selection.isEmpty()) {
            throw new IllegalArgumentException("Не выбрано ни одного слота");
        }
        return selection;
    }

    private static List<Integer> parseIndexList(Object raw, int max, String field) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException("Поле " + field + " должно быть списком чисел");
        }
        List<Integer> result = new java.util.ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Double number)) {
                throw new IllegalArgumentException("Поле " + field + " должно быть списком чисел");
            }
            int index = (int) Math.round(number);
            if (index < 0 || index > max) {
                throw new IllegalArgumentException(
                        "Поле " + field + ": номер должен быть в диапазоне 0-" + max);
            }
            result.add(index);
        }
        return result;
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
