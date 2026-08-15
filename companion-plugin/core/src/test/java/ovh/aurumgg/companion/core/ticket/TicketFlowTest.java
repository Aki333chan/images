package ovh.aurumgg.companion.core.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    @DisplayName("Недоступная панель даёт понятную ошибку без адреса внутри")
    void reportsUnreachablePanel() throws Exception {
        FakePanel panel = startPanel(200, "{}");
        String baseUrl = panel.baseUrl();
        panel.stop(); // панель «легла»

        CompanionConfig config =
                new CompanionConfig("127.0.0.1", 0, "server-1-secret-token-EXAMPLE", baseUrl, "srv-42", 10);
        TicketClient.TicketException error = assertThrows(
                TicketClient.TicketException.class,
                () -> new TicketClient(config).send(UUID.randomUUID(), "Steve", "текст"));
        assertEquals("панель недоступна", error.getMessage());
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
