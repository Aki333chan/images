package ovh.aurumgg.companion.core.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.companion.core.CompanionConfig;
import ovh.aurumgg.companion.core.json.JsonParser;

class TicketCooldownTest {

    @Test
    @DisplayName("Первое обращение проходит сразу")
    void firstCallPasses() {
        TicketCooldown cooldown = new TicketCooldown(10, () -> 0L);
        assertEquals(0, cooldown.secondsRemaining(UUID.randomUUID()));
    }

    @Test
    @DisplayName("Повтор внутри окна отклоняется с остатком времени")
    void blocksWithinWindow() {
        AtomicLong now = new AtomicLong(0);
        TicketCooldown cooldown = new TicketCooldown(10, now::get);
        UUID player = UUID.randomUUID();

        assertEquals(0, cooldown.secondsRemaining(player));
        now.set(3_000);
        assertEquals(7, cooldown.secondsRemaining(player));
    }

    @Test
    @DisplayName("После истечения окна снова можно писать")
    void allowsAfterWindow() {
        AtomicLong now = new AtomicLong(0);
        TicketCooldown cooldown = new TicketCooldown(10, now::get);
        UUID player = UUID.randomUUID();

        cooldown.secondsRemaining(player);
        now.set(10_000);
        assertEquals(0, cooldown.secondsRemaining(player));
    }

    @Test
    @DisplayName("Кулдаун у каждого игрока свой")
    void cooldownIsPerPlayer() {
        AtomicLong now = new AtomicLong(0);
        TicketCooldown cooldown = new TicketCooldown(10, now::get);

        assertEquals(0, cooldown.secondsRemaining(UUID.randomUUID()));
        assertEquals(0, cooldown.secondsRemaining(UUID.randomUUID()));
    }

    @Test
    @DisplayName("Нулевой кулдаун никого не блокирует")
    void zeroCooldownNeverBlocks() {
        TicketCooldown cooldown = new TicketCooldown(0, () -> 0L);
        UUID player = UUID.randomUUID();
        assertEquals(0, cooldown.secondsRemaining(player));
        assertEquals(0, cooldown.secondsRemaining(player));
    }
}

class TicketClientTest {

    /** Поднимает подставную панель и записывает пришедшие запросы. */
    private record FakePanel(HttpServer server, List<String> bodies, List<String> auth, List<String> paths) {
        void stop() {
            server.stop(0);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }
    }

    private FakePanel startPanel(int status, String response) throws Exception {
        List<String> bodies = new ArrayList<>();
        List<String> auth = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            auth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return new FakePanel(server, bodies, auth, paths);
    }

    private CompanionConfig configFor(FakePanel panel) {
        return new CompanionConfig("127.0.0.1", 0, "server-1-secret-token-EXAMPLE", panel.baseUrl(), "srv-42", 10);
    }

    @Test
    @DisplayName("Отправляет UUID, ник и текст на internal-эндпоинт панели")
    void sendsTicket() throws Exception {
        FakePanel panel = startPanel(201, "{\"ticketId\":\"t-1\",\"created\":true}");
        try {
            TicketClient client = new TicketClient(configFor(panel));
            UUID player = UUID.randomUUID();

            TicketClient.Result result = client.send(player, "Steve", "меня загрифили");

            assertTrue(result.created());
            assertEquals("t-1", result.ticketId());
            assertEquals("/api/internal/minecraft/servers/srv-42/tickets", panel.paths().get(0));
            assertEquals("Bearer server-1-secret-token-EXAMPLE", panel.auth().get(0));

            Map<String, Object> body = JsonParser.parseObject(panel.bodies().get(0));
            assertEquals(player.toString(), body.get("playerUuid"));
            assertEquals("Steve", body.get("playerName"));
            assertEquals("меня загрифили", body.get("text"));
        } finally {
            panel.stop();
        }
    }

    @Test
    @DisplayName("Различает создание тикета и добавление сообщения")
    void distinguishesCreatedFromAppended() throws Exception {
        FakePanel panel = startPanel(201, "{\"ticketId\":\"t-1\",\"created\":false}");
        try {
            TicketClient.Result result =
                    new TicketClient(configFor(panel)).send(UUID.randomUUID(), "Steve", "ещё вопрос");
            assertFalse(result.created());
        } finally {
            panel.stop();
        }
    }

    @Test
    @DisplayName("Текст с кавычками и переводом строки не ломает JSON запроса")
    void escapesTrickyText() throws Exception {
        FakePanel panel = startPanel(201, "{\"ticketId\":\"t-1\",\"created\":true}");
        try {
            String tricky = "он сказал \"привет\"\nи ушёл";
            new TicketClient(configFor(panel)).send(UUID.randomUUID(), "Steve", tricky);
            Map<String, Object> body = JsonParser.parseObject(panel.bodies().get(0));
            assertEquals(tricky, body.get("text"));
        } finally {
            panel.stop();
        }
    }

    @Test
    @DisplayName("Отказ панели по токену сообщается отдельно и не раскрывает секрет")
    void reportsAuthFailureWithoutLeakingToken() throws Exception {
        FakePanel panel = startPanel(401, "{\"message\":\"нет\"}");
        try {
            TicketClient client = new TicketClient(configFor(panel));
            TicketClient.TicketException error = assertThrows(
                    TicketClient.TicketException.class,
                    () -> client.send(UUID.randomUUID(), "Steve", "текст"));
            assertTrue(error.getMessage().contains("токен"));
            assertFalse(error.getMessage().contains("server-1-secret-token-EXAMPLE"));
        } finally {
            panel.stop();
        }
    }

    @Test
    @DisplayName("Недоступная панель: названа причина и адрес, но не токен")
    void reportsUnreachablePanel() throws Exception {
        FakePanel panel = startPanel(200, "{}");
        String baseUrl = panel.baseUrl();
        panel.stop(); // панель «легла»

        String token = "server-1-secret-token-EXAMPLE";
        CompanionConfig config = new CompanionConfig("127.0.0.1", 0, token, baseUrl, "srv-42", 10);
        TicketClient.TicketException error = assertThrows(
                TicketClient.TicketException.class,
                () -> new TicketClient(config).send(UUID.randomUUID(), "Steve", "текст"));

        // Прежде здесь на все случаи стояло одно «панель недоступна», и по нему
        // нельзя было отличить закрытый порт от опечатки в адресе. Адрес в
        // сообщении теперь есть намеренно: это строка из config.yml того же
        // сервера. Токен — по-прежнему нет и быть не должно.
        assertTrue(error.getMessage().contains("подключиться"), error.getMessage());
        assertTrue(error.getMessage().contains("127.0.0.1"), error.getMessage());
        assertFalse(error.getMessage().contains(token), error.getMessage());
    }

    /**
     * Панель так, как её видит сеть: сервер, который рвёт соединение на
     * запросе с «Upgrade».
     *
     * Ровно это делает настоящая панель. Она на Node, и к её HTTP-серверу
     * прицеплен socket.io; как только у Node-сервера появляется обработчик
     * события upgrade, запросы с этим заголовком уходят в него мимо обычного
     * конвейера, а socket.io на чужом пути молча закрывает сокет.
     *
     * Обычный FakePanel выше этого не воспроизводит: он на
     * com.sun.net.httpserver, который заголовок Upgrade просто игнорирует и
     * отвечает как ни в чём не бывало. Из-за этого тесты были зелёными, а на
     * живой панели тикеты не отправлялись вообще ни разу.
     */
    private static ServerSocket startUpgradeHostilePanel() throws Exception {
        ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                    boolean upgrade = false;
                    for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("upgrade:")) upgrade = true;
                    }
                    if (upgrade) continue; // закрываем, не ответив, — как socket.io

                    byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                    OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                } catch (IOException e) {
                    return;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return socket;
    }

    @Test
    @DisplayName("Панель с socket.io: клиент не должен просить HTTP/2")
    void survivesUpgradeHostilePanel() throws Exception {
        // Регрессия на настоящую поломку: по умолчанию HttpClient просит
        // HTTP/2 через «Upgrade: h2c», панель на это рвёт соединение, и
        // тикеты не уходили никогда. curl и wget при этом до панели
        // доходили — они шлют обычный HTTP/1.1, — из-за чего проверялась
        // сеть, в которой всё было цело.
        try (ServerSocket panel = startUpgradeHostilePanel()) {
            String baseUrl = "http://127.0.0.1:" + panel.getLocalPort();
            CompanionConfig config = new CompanionConfig(
                    "127.0.0.1", 0, "server-1-secret-token-EXAMPLE", baseUrl, "srv-42", 10);

            assertNull(new TicketClient(config).checkPanel(), "боевой клиент обязан работать");
        }
    }

    @Test
    @DisplayName("Разрыв без ответа не выдаётся за «панель недоступна»")
    void namesConnectionResetHonestly() throws Exception {
        // Если такое всё же случится, «недоступна» будет прямой ложью: сеть
        // исправна, соединение состоялось. Человека это уводит проверять
        // туннель и фаервол — там всё в порядке, и поиск заходит в тупик.
        try (ServerSocket panel = startUpgradeHostilePanel()) {
            String baseUrl = "http://127.0.0.1:" + panel.getLocalPort();
            CompanionConfig config = new CompanionConfig(
                    "127.0.0.1", 0, "server-1-secret-token-EXAMPLE", baseUrl, "srv-42", 10);

            // Клиент с настройками по умолчанию — то есть с HTTP/2.
            TicketClient broken = new TicketClient(
                    config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
            String problem = broken.checkPanel();
            assertNotNull(problem);
            assertTrue(problem.contains("разорвала соединение"), problem);
            assertFalse(problem.contains("недоступна"), problem);
        }
    }

    @Test
    @DisplayName("Несуществующий хост панели назван проблемой адреса")
    void reportsUnknownHost() {
        CompanionConfig config = new CompanionConfig(
                "127.0.0.1", 0, "server-1-secret-token-EXAMPLE",
                "http://net-takogo-hosta.invalid:3001", "srv-42", 10);
        TicketClient.TicketException error = assertThrows(
                TicketClient.TicketException.class,
                () -> new TicketClient(config).send(UUID.randomUUID(), "Steve", "текст"));
        assertTrue(error.getMessage().contains("panel.base-url"), error.getMessage());
    }

    @Test
    @DisplayName("Адрес, из которого URI не собирается, не роняет задачу стектрейсом")
    void reportsMalformedBaseUrl() {
        // Кириллица, пробел, забытая схема — всё это IllegalArgumentException
        // из URI.create. Он летел необработанным прямо из асинхронной задачи:
        // игрок видел «попробуй позже», а в консоли был стектрейс без единого
        // упоминания config.yml.
        for (String bad : new String[] {"http://кириллица:3001", "10.0.0.1:3001", "не адрес"}) {
            CompanionConfig config = new CompanionConfig(
                    "127.0.0.1", 0, "server-1-secret-token-EXAMPLE", bad, "srv-42", 10);
            TicketClient.TicketException error = assertThrows(
                    TicketClient.TicketException.class,
                    () -> new TicketClient(config).send(UUID.randomUUID(), "Steve", "текст"),
                    "адрес: " + bad);
            assertTrue(error.getMessage().contains("panel.base-url"), bad + " → " + error.getMessage());
        }
    }

    @Test
    @DisplayName("Проверка связи на старте: молчит при живой панели, объясняет при мёртвой")
    void checkPanelReportsOnlyProblems() throws Exception {
        FakePanel panel = startPanel(200, "{\"status\":\"ok\"}");
        String baseUrl = panel.baseUrl();
        try {
            assertNull(new TicketClient(configFor(panel)).checkPanel());
        } finally {
            panel.stop();
        }

        CompanionConfig dead = new CompanionConfig(
                "127.0.0.1", 0, "server-1-secret-token-EXAMPLE", baseUrl, "srv-42", 10);
        String problem = new TicketClient(dead).checkPanel();
        assertNotNull(problem);
        assertTrue(problem.contains("подключиться"), problem);
    }

    @Test
    @DisplayName("Непонятный ответ панели не роняет плагин")
    void handlesGarbageResponse() throws Exception {
        FakePanel panel = startPanel(200, "не json");
        try {
            TicketClient client = new TicketClient(configFor(panel));
            assertThrows(
                    TicketClient.TicketException.class,
                    () -> client.send(UUID.randomUUID(), "Steve", "текст"));
        } finally {
            panel.stop();
        }
    }
}

class CompanionConfigTest {

    @Test
    @DisplayName("Плагин не поднимет HTTP с токеном-заглушкой")
    void rejectsPlaceholderToken() {
        CompanionConfig config = new CompanionConfig(
                "0.0.0.0", 8085, CompanionConfig.PLACEHOLDER_TOKEN, "http://10.0.0.1:3001", "srv", 10);
        assertTrue(config.httpConfigProblem().contains("token"));
    }

    @Test
    @DisplayName("Короткий токен отвергается")
    void rejectsShortToken() {
        CompanionConfig config =
                new CompanionConfig("0.0.0.0", 8085, "коротко", "http://10.0.0.1:3001", "srv", 10);
        assertTrue(config.httpConfigProblem().contains("короче"));
    }

    @Test
    @DisplayName("Корректная конфигурация проблем не имеет")
    void acceptsValidConfig() {
        CompanionConfig config = new CompanionConfig(
                "0.0.0.0", 8085, "long-enough-token-EXAMPLE", "http://10.0.0.1:3001", "srv", 10);
        assertNull(config.httpConfigProblem());
        assertNull(config.ticketConfigProblem());
    }

    @Test
    @DisplayName("Без адреса панели и server-id тикеты отключаются")
    void requiresPanelSettingsForTickets() {
        CompanionConfig noUrl =
                new CompanionConfig("0.0.0.0", 8085, "long-enough-token-EXAMPLE", "", "srv", 10);
        assertTrue(noUrl.ticketConfigProblem().contains("base-url"));

        CompanionConfig noServer = new CompanionConfig(
                "0.0.0.0", 8085, "long-enough-token-EXAMPLE", "http://10.0.0.1:3001", "", 10);
        assertTrue(noServer.ticketConfigProblem().contains("server-id"));
    }

    @Test
    @DisplayName("Некорректный порт отвергается")
    void rejectsBadPort() {
        CompanionConfig config = new CompanionConfig(
                "0.0.0.0", 70000, "long-enough-token-EXAMPLE", "http://10.0.0.1:3001", "srv", 10);
        assertTrue(config.httpConfigProblem().contains("порт"));
    }

    @Test
    @DisplayName("Порт плагина в адресе панели — предупреждение о перепутанных полях")
    void warnsWhenPanelUrlUsesPluginPort() {
        // Настоящий случай: в panel.base-url вписали порт, выданный плагину
        // secondary allocation. Плагин стучится в собственный номер порта на
        // чужой машине, соединения нет — и «не удалось подключиться»
        // отправляет чинить сеть, в которой всё цело.
        CompanionConfig confused = new CompanionConfig(
                "0.0.0.0", 8083, "long-enough-token-EXAMPLE", "http://10.0.0.1:8083", "srv", 10);
        String warning = confused.ticketConfigWarning();
        assertNotNull(warning);
        assertTrue(warning.contains("8083"), warning);
        assertTrue(warning.contains("http.port"), warning);
        // Предупреждение, а не отказ: тикеты остаются включёнными.
        assertNull(confused.ticketConfigProblem());

        CompanionConfig fine = new CompanionConfig(
                "0.0.0.0", 8083, "long-enough-token-EXAMPLE", "http://10.0.0.1:3001", "srv", 10);
        assertNull(fine.ticketConfigWarning());
    }

    @Test
    @DisplayName("Лишний слэш не мешает распознать перепутанные порты")
    void warningSurvivesTrailingSlash() {
        CompanionConfig confused = new CompanionConfig(
                "0.0.0.0", 8083, "long-enough-token-EXAMPLE", "http://10.0.0.1:8083/", "srv", 10);
        assertNotNull(confused.ticketConfigWarning());
    }
}

/** Регрессия: токен уходит в HTTP-заголовок, а заголовки обязаны быть ASCII. */
class TokenCharsetTest {

    @Test
    @DisplayName("Токен с кириллицей отвергается на старте, а не падает при первом /ticket")
    void rejectsNonAsciiToken() {
        CompanionConfig config = new CompanionConfig(
                "0.0.0.0", 8085, "секретный-токен-достаточной-длины", "http://10.0.0.1:3001", "srv", 10);
        assertTrue(config.httpConfigProblem().contains("ASCII"));
        assertNotNull(config.ticketConfigProblem());
    }

    @Test
    @DisplayName("Токен с пробелом внутри тоже отвергается")
    void rejectsTokenWithSpace() {
        CompanionConfig config = new CompanionConfig(
                "0.0.0.0", 8085, "token with spaces inside", "http://10.0.0.1:3001", "srv", 10);
        assertTrue(config.httpConfigProblem().contains("ASCII"));
    }

    @Test
    @DisplayName("Вывод openssl rand -base64 32 проходит проверку")
    void acceptsOpensslStyleToken() {
        CompanionConfig config = new CompanionConfig(
                "0.0.0.0", 8085, "Qk9Yb3RIZWxsb1dvcmxkMTIzNDU2Nzg5MA==", "http://10.0.0.1:3001", "srv", 10);
        assertNull(config.httpConfigProblem());
    }
}
