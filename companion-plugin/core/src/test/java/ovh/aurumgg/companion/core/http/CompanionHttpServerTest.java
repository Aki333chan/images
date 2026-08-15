package ovh.aurumgg.companion.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.companion.core.CompanionConfig;
import ovh.aurumgg.companion.core.FakeGameBridge;
import ovh.aurumgg.companion.core.json.JsonParser;

/**
 * Тесты гоняют настоящий HTTP-сервер плагина на случайном порту.
 * Bukkit здесь не нужен — игра подменена FakeGameBridge.
 */
class CompanionHttpServerTest {

    private static final String TOKEN = "s3cret-companion-token-EXAMPLE42";

    private FakeGameBridge bridge;
    private CompanionHttpServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        bridge = new FakeGameBridge();
        CompanionConfig config =
                new CompanionConfig("127.0.0.1", 0, TOKEN, "http://10.0.0.1:3001", "srv-1", 10);
        server = new CompanionHttpServer(config, bridge, msg -> {});
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + server.boundPort();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("Без токена доступ закрыт")
    void rejectsMissingToken() throws Exception {
        assertEquals(401, get("/players", null).statusCode());
    }

    @Test
    @DisplayName("С неверным токеном доступ закрыт")
    void rejectsWrongToken() throws Exception {
        assertEquals(401, get("/players", "wrong-token-value").statusCode());
    }

    @Test
    @DisplayName("Токен принимается и как «Bearer x», и голым")
    void acceptsBothTokenForms() throws Exception {
        assertEquals(200, get("/players", TOKEN).statusCode());

        HttpResponse<String> bare = client.send(
                HttpRequest.newBuilder(URI.create(base + "/players"))
                        .header("Authorization", TOKEN)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, bare.statusCode());
    }

    @Test
    @DisplayName("GET /players отдаёт UUID, ник, здоровье, мир, координаты и пинг")
    void listsPlayers() throws Exception {
        HttpResponse<String> response = get("/players", TOKEN);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));

        Map<String, Object> body = JsonParser.parseObject(response.body());
        List<?> players = (List<?>) body.get("players");
        assertEquals(1, players.size());

        Map<?, ?> steve = (Map<?, ?>) players.get(0);
        assertEquals(FakeGameBridge.STEVE.toString(), steve.get("uuid"));
        assertEquals("Steve", steve.get("name"));
        assertEquals(18.5, steve.get("health"));
        assertEquals(20.0, steve.get("maxHealth"));
        assertEquals("world", steve.get("world"));
        assertEquals(42.0, steve.get("ping"));
        // Координаты округляются до сотых.
        assertEquals(100.26, steve.get("x"));
        assertEquals(-200.5, steve.get("z"));
    }

    @Test
    @DisplayName("GET инвентаря отдаёт предметы, броню, оффхенд, зачарования и имя")
    void returnsInventory() throws Exception {
        HttpResponse<String> response = get("/players/" + FakeGameBridge.STEVE + "/inventory", TOKEN);
        assertEquals(200, response.statusCode());

        Map<String, Object> body = JsonParser.parseObject(response.body());
        List<?> items = (List<?>) body.get("items");
        assertEquals(2, items.size());

        Map<?, ?> sword = (Map<?, ?>) items.get(0);
        assertEquals("minecraft:diamond_sword", sword.get("id"));
        assertEquals(1.0, sword.get("count"));
        // Кавычки внутри имени предмета не должны ломать JSON.
        assertEquals("Меч \"Гроза\"", sword.get("displayName"));
        assertEquals(5.0, ((Map<?, ?>) sword.get("enchantments")).get("minecraft:sharpness"));
        assertEquals(List.of("Строка описания"), sword.get("lore"));

        Map<?, ?> stone = (Map<?, ?>) items.get(1);
        assertEquals(null, stone.get("displayName"));
        assertEquals(64.0, stone.get("count"));

        assertEquals(1, ((List<?>) body.get("armor")).size());
        assertEquals("minecraft:shield", ((Map<?, ?>) body.get("offhand")).get("id"));
    }

    @Test
    @DisplayName("Инвентарь оффлайн-игрока — 404, а не пустой объект")
    void inventoryOfOfflinePlayerIs404() throws Exception {
        bridge.steveOnline = false;
        assertEquals(404, get("/players/" + FakeGameBridge.STEVE + "/inventory", TOKEN).statusCode());
    }

    @Test
    @DisplayName("Некорректный UUID — 400")
    void rejectsBadUuid() throws Exception {
        assertEquals(400, get("/players/not-a-uuid/inventory", TOKEN).statusCode());
    }

    @Test
    @DisplayName("Неизвестный маршрут — 404")
    void unknownRouteIs404() throws Exception {
        assertEquals(404, get("/unknown-route", TOKEN).statusCode());
    }

    @Test
    @DisplayName("POST кладёт предмет в слот")
    void setsInventorySlot() throws Exception {
        HttpResponse<String> response = post(
                "/players/" + FakeGameBridge.STEVE + "/inventory/5",
                TOKEN,
                "{\"id\":\"minecraft:stone\",\"count\":3}");
        assertEquals(200, response.statusCode());
        assertEquals(List.of("set:5:minecraft:stonex3"), bridge.slotWrites);
    }

    @Test
    @DisplayName("POST с пустым телом очищает слот")
    void clearsInventorySlot() throws Exception {
        assertEquals(200, post("/players/" + FakeGameBridge.STEVE + "/inventory/7", TOKEN, "").statusCode());
        assertEquals(List.of("clear:7"), bridge.slotWrites);
    }

    @Test
    @DisplayName("Слот вне диапазона 0-35 отклоняется")
    void rejectsSlotOutOfRange() throws Exception {
        assertEquals(400, post("/players/" + FakeGameBridge.STEVE + "/inventory/99", TOKEN, "{}").statusCode());
        assertEquals(400, post("/players/" + FakeGameBridge.STEVE + "/inventory/-1", TOKEN, "{}").statusCode());
        assertTrue(bridge.slotWrites.isEmpty());
    }

    @Test
    @DisplayName("Неизвестный материал — 404, изменений нет")
    void rejectsUnknownMaterial() throws Exception {
        HttpResponse<String> response = post(
                "/players/" + FakeGameBridge.STEVE + "/inventory/1",
                TOKEN,
                "{\"id\":\"made_up_item\",\"count\":1}");
        assertEquals(404, response.statusCode());
        assertTrue(bridge.slotWrites.isEmpty());
    }

    @Test
    @DisplayName("Битый JSON в теле — 400, а не 500")
    void rejectsBrokenJson() throws Exception {
        assertEquals(
                400,
                post("/players/" + FakeGameBridge.STEVE + "/inventory/1", TOKEN, "{broken").statusCode());
    }

    @Test
    @DisplayName("count больше стака отклоняется")
    void rejectsTooLargeCount() throws Exception {
        assertEquals(
                400,
                post(
                                "/players/" + FakeGameBridge.STEVE + "/inventory/1",
                                TOKEN,
                                "{\"id\":\"minecraft:stone\",\"count\":999}")
                        .statusCode());
    }

    @Test
    @DisplayName("В ответах об ошибке нет токена")
    void errorsDoNotLeakToken() throws Exception {
        HttpResponse<String> response = get("/players", "wrong-token-value");
        assertFalse(response.body().contains(TOKEN));
        assertNotNull(response.body());
    }
}
