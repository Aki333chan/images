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
    private ovh.aurumgg.companion.core.webtoken.WebTokenStore webTokens;
    private HttpClient client;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        bridge = new FakeGameBridge();
        CompanionConfig config =
                new CompanionConfig("127.0.0.1", 0, TOKEN, "http://10.0.0.1:3001", "srv-1", 10);
        webTokens = new ovh.aurumgg.companion.core.webtoken.WebTokenStore(java.time.Duration.ofMinutes(5));
        server = new CompanionHttpServer(config, bridge, msg -> {}, webTokens);
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

    // ---------------------------------------------- Выдача и очистка списком

    private String givePath() {
        return "/players/" + FakeGameBridge.STEVE + "/inventory/give";
    }

    private String clearPath() {
        return "/players/" + FakeGameBridge.STEVE + "/inventory/clear";
    }

    @Test
    @DisplayName("Выдача списком доезжает до игры целиком и по порядку")
    void givesItemList() throws Exception {
        HttpResponse<String> response = post(
                givePath(),
                TOKEN,
                "{\"items\":[{\"id\":\"minecraft:stone\",\"count\":64},"
                        + "{\"id\":\"minecraft:apple\",\"count\":3}]}");
        assertEquals(200, response.statusCode());
        assertEquals(List.of("minecraft:stonex64", "minecraft:applex3"), bridge.gives);
        assertTrue(response.body().contains("\"given\":64"), response.body());
    }

    @Test
    @DisplayName("count по умолчанию — один предмет")
    void giveDefaultsToOne() throws Exception {
        assertEquals(200, post(givePath(), TOKEN, "{\"items\":[{\"id\":\"minecraft:stone\"}]}").statusCode());
        assertEquals(List.of("minecraft:stonex1"), bridge.gives);
    }

    @Test
    @DisplayName("Неизвестный предмет не отменяет остальные строки")
    void giveReportsPerLine() throws Exception {
        HttpResponse<String> response = post(
                givePath(),
                TOKEN,
                "{\"items\":[{\"id\":\"made_up\",\"count\":1},"
                        + "{\"id\":\"minecraft:stone\",\"count\":1}]}");
        // 200, а не 400: запрос исполнен, просто одна строка не легла — и это
        // видно построчно, иначе человек не поймёт, какая именно.
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Неизвестный предмет"), response.body());
        assertEquals(List.of("made_upx1", "minecraft:stonex1"), bridge.gives);
    }

    @Test
    @DisplayName("Переполненный инвентарь — 200 с числом, сколько легло")
    void giveReportsLeftover() throws Exception {
        bridge.freeSpace = 0;
        HttpResponse<String> response =
                post(givePath(), TOKEN, "{\"items\":[{\"id\":\"minecraft:stone\",\"count\":64}]}");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"given\":0"), response.body());
        assertTrue(response.body().contains("Инвентарь заполнен"), response.body());
    }

    @Test
    @DisplayName("Пустой и слишком длинный список выдачи отклоняются")
    void rejectsBadGiveList() throws Exception {
        assertEquals(400, post(givePath(), TOKEN, "{\"items\":[]}").statusCode());
        assertEquals(400, post(givePath(), TOKEN, "{}").statusCode());
        assertEquals(400, post(givePath(), TOKEN, "").statusCode());

        StringBuilder many = new StringBuilder("{\"items\":[");
        for (int i = 0; i <= CompanionHttpServer.MAX_GIVE_ENTRIES; i++) {
            if (i > 0) many.append(',');
            many.append("{\"id\":\"minecraft:stone\"}");
        }
        many.append("]}");
        assertEquals(400, post(givePath(), TOKEN, many.toString()).statusCode());
        assertTrue(bridge.gives.isEmpty());
    }

    @Test
    @DisplayName("Абсурдный count отклоняется, а не собирает миллион стаков")
    void rejectsAbsurdGiveCount() throws Exception {
        assertEquals(
                400,
                post(givePath(), TOKEN, "{\"items\":[{\"id\":\"minecraft:stone\",\"count\":64000000}]}")
                        .statusCode());
        assertEquals(
                400,
                post(givePath(), TOKEN, "{\"items\":[{\"id\":\"minecraft:stone\",\"count\":0}]}")
                        .statusCode());
        assertTrue(bridge.gives.isEmpty());
    }

    @Test
    @DisplayName("Выдача офлайн-игроку — 404 с машиночитаемым кодом")
    void giveOfflineIs404() throws Exception {
        bridge.steveOnline = false;
        HttpResponse<String> response =
                post(givePath(), TOKEN, "{\"items\":[{\"id\":\"minecraft:stone\"}]}");
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("player-offline"), response.body());
    }

    @Test
    @DisplayName("Очистка выбранных слотов, брони и второй руки")
    void clearsSelection() throws Exception {
        HttpResponse<String> response =
                post(clearPath(), TOKEN, "{\"slots\":[0,9],\"armor\":[3],\"offhand\":true}");
        assertEquals(200, response.statusCode());
        assertEquals(List.of("slots=[0, 9] armor=[3] offhand=true"), bridge.clears);
    }

    @Test
    @DisplayName("Полная очистка — только по явному all")
    void clearsEverything() throws Exception {
        assertEquals(200, post(clearPath(), TOKEN, "{\"all\":true}").statusCode());
        assertEquals(List.of("all"), bridge.clears);
    }

    @Test
    @DisplayName("Пустой выбор — 400, а не молчаливая полная очистка")
    void emptySelectionIsNotWipe() throws Exception {
        assertEquals(400, post(clearPath(), TOKEN, "{}").statusCode());
        assertEquals(400, post(clearPath(), TOKEN, "{\"slots\":[]}").statusCode());
        assertEquals(400, post(clearPath(), TOKEN, "").statusCode());
        assertEquals(400, post(clearPath(), TOKEN, "{\"all\":false}").statusCode());
        assertTrue(bridge.clears.isEmpty());
    }

    @Test
    @DisplayName("Слот и индекс брони вне диапазона отклоняются")
    void rejectsOutOfRangeSelection() throws Exception {
        assertEquals(400, post(clearPath(), TOKEN, "{\"slots\":[36]}").statusCode());
        assertEquals(400, post(clearPath(), TOKEN, "{\"armor\":[4]}").statusCode());
        assertEquals(400, post(clearPath(), TOKEN, "{\"slots\":[\"1\"]}").statusCode());
        assertTrue(bridge.clears.isEmpty());
    }

    @Test
    @DisplayName("give и clear не разбираются как номер слота")
    void giveAndClearAreNotSlots() throws Exception {
        // Пути одной формы: /inventory/{slot} против /inventory/give. Если
        // порядок маршрутов перепутать, сюда прилетит 400 «некорректный слот».
        assertEquals(200, post(clearPath(), TOKEN, "{\"all\":true}").statusCode());
        assertTrue(bridge.slotWrites.isEmpty());
    }

    // ------------------------------------------------------- бонусы гильдий

    private HttpResponse<String> delete(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("Бонусы гильдии отдаются со всем, что нужно панели")
    void listsGuildBonuses() throws Exception {
        bridge.guildsInstalled = true;
        HttpResponse<String> response = get("/guilds/7/bonuses", TOKEN);
        assertEquals(200, response.statusCode());
        // Название вида приходит от плагина, а не собирается в панели: иначе
        // новый вид бонуса пришлось бы добавлять в двух местах.
        assertTrue(response.body().contains("\"title\":\"Опыт\""), response.body());
        assertTrue(response.body().contains("\"multiplier\":true"), response.body());
        // Постоянный бонус — ноль, а не отсутствующее поле.
        assertTrue(response.body().contains("\"expiresAt\":0"), response.body());
    }

    @Test
    @DisplayName("Выдача бонуса доезжает до плагина с сроком и величиной")
    void grantsGuildBonus() throws Exception {
        bridge.guildsInstalled = true;
        HttpResponse<String> response = post("/guilds/7/bonuses", TOKEN,
                "{\"type\":\"experience\",\"magnitude\":2,\"seconds\":3600,\"actor\":\"ГМ\"}");
        assertEquals(200, response.statusCode());
        assertEquals(List.of("grant 7 experience 2.0 3600"), bridge.bonusCalls);
    }

    @Test
    @DisplayName("Без срока бонус выдаётся навсегда")
    void grantsPermanentBonus() throws Exception {
        bridge.guildsInstalled = true;
        assertEquals(200, post("/guilds/7/bonuses", TOKEN,
                "{\"type\":\"experience\",\"magnitude\":2,\"actor\":\"ГМ\"}").statusCode());
        assertEquals(List.of("grant 7 experience 2.0 0"), bridge.bonusCalls);
    }

    @Test
    @DisplayName("Неизвестный вид — отказ с объяснением, а не «плагина нет»")
    void rejectsUnknownBonusType() throws Exception {
        bridge.guildsInstalled = true;
        // Разные причины требуют разных действий: «нет плагина» — идти ставить
        // плагин, «нет такого вида» — исправить запрос.
        HttpResponse<String> response = post("/guilds/7/bonuses", TOKEN,
                "{\"type\":\"вечнаяжизнь\",\"magnitude\":9,\"actor\":\"ГМ\"}");
        assertEquals(409, response.statusCode(), response.body());
        assertTrue(response.body().contains("Неизвестный вид"), response.body());
    }

    @Test
    @DisplayName("Без вида бонуса — 400")
    void requiresBonusType() throws Exception {
        bridge.guildsInstalled = true;
        assertEquals(400, post("/guilds/7/bonuses", TOKEN, "{\"magnitude\":2}").statusCode());
        assertTrue(bridge.bonusCalls.isEmpty());
    }

    @Test
    @DisplayName("Снятие бонуса — DELETE с видом в пути")
    void revokesGuildBonus() throws Exception {
        bridge.guildsInstalled = true;
        assertEquals(200, delete("/guilds/7/bonuses/experience", "{\"actor\":\"ГМ\"}").statusCode());
        assertEquals(List.of("revoke 7 experience"), bridge.bonusCalls);
    }

    @Test
    @DisplayName("Без плагина гильдий бонусы отвечают 503, а не пустым списком")
    void bonusesWithoutGuildsPlugin() throws Exception {
        // Пустой список означал бы «бонусов нет», и человек бы их выдавал,
        // не понимая, почему ничего не происходит.
        bridge.guildsInstalled = false;
        HttpResponse<String> response = get("/guilds/7/bonuses", TOKEN);
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("guilds-unavailable"), response.body());
    }

    @Test
    @DisplayName("В ответах об ошибке нет токена")
    void errorsDoNotLeakToken() throws Exception {
        HttpResponse<String> response = get("/players", "wrong-token-value");
        assertFalse(response.body().contains(TOKEN));
        assertNotNull(response.body());
    }

    // ---------------------------------------------- Каталог плагинов сервера

    @Test
    @DisplayName("GET /plugins отдаёт имя, версию и признак включённости")
    void listsInstalledPlugins() throws Exception {
        bridge.install("LuckPerms");

        HttpResponse<String> response = get("/plugins", TOKEN);

        assertEquals(200, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        List<?> plugins = (List<?>) body.get("plugins");
        assertEquals(2, plugins.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) plugins.get(1);
        assertEquals("LuckPerms", second.get("name"));
        assertEquals("1.0.0", second.get("version"));
        assertEquals(Boolean.TRUE, second.get("enabled"));
    }

    // ------------------------------------------------- Автодополнение команд

    @Test
    @DisplayName("GET /complete отдаёт варианты для незавершённого слова")
    void completesCommandName() throws Exception {
        HttpResponse<String> response = get("/complete?line=g", TOKEN);

        assertEquals(200, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        assertEquals(List.of("gamemode", "give"), body.get("suggestions"));
    }

    @Test
    @DisplayName("Ведущий слэш срезается: CommandMap ждёт строку без него")
    void stripsLeadingSlash() throws Exception {
        assertEquals(200, get("/complete?line=%2Fg", TOKEN).statusCode());
        assertEquals(List.of("g"), bridge.completedLines);
    }

    @Test
    @DisplayName("Пустая строка — это все команды, а не ошибка")
    void completesEmptyLine() throws Exception {
        HttpResponse<String> response = get("/complete?line=", TOKEN);

        assertEquals(200, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        assertEquals(4, ((List<?>) body.get("suggestions")).size());
        assertEquals(List.of(""), bridge.completedLines);
    }

    @Test
    @DisplayName("Аргумент команды дополняется силами сервера (ники онлайна)")
    void completesArgument() throws Exception {
        HttpResponse<String> response = get("/complete?line=gamemode%20creative%20", TOKEN);

        assertEquals(200, response.statusCode());
        assertEquals(List.of("Steve"), JsonParser.parseObject(response.body()).get("suggestions"));
        // Пробелы важны: по ним сервер понимает, какой аргумент набирается.
        assertEquals(List.of("gamemode creative "), bridge.completedLines);
    }

    @Test
    @DisplayName("Автодополнение закрыто токеном, как и всё остальное")
    void completionRequiresToken() throws Exception {
        assertEquals(401, get("/complete?line=g", null).statusCode());
        assertTrue(bridge.completedLines.isEmpty());
    }

    @Test
    @DisplayName("Простыня вместо команды обрезается до 256 символов")
    void limitsCompletionLineLength() throws Exception {
        assertEquals(200, get("/complete?line=" + "a".repeat(1000), TOKEN).statusCode());
        assertEquals(256, bridge.completedLines.get(0).length());
    }

    // ------------------------------------------------------ Права (LuckPerms)

    @Test
    @DisplayName("без LuckPerms права отвечают кодом requires-luckperms, а не падают")
    void permissionsWithoutLuckPerms() throws Exception {
        HttpResponse<String> response = get("/players/" + FakeGameBridge.STEVE + "/permissions", TOKEN);

        assertEquals(404, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        assertEquals("requires-luckperms", body.get("code"));
    }

    @Test
    @DisplayName("с LuckPerms отдаётся основная группа, группы и ноды")
    void permissionsWithLuckPerms() throws Exception {
        bridge.install("LuckPerms");

        HttpResponse<String> response = get("/players/" + FakeGameBridge.STEVE + "/permissions", TOKEN);

        assertEquals(200, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        assertEquals("default", body.get("primaryGroup"));
        assertEquals(List.of("default"), body.get("groups"));
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) ((List<?>) body.get("permissions")).get(0);
        assertEquals("essentials.fly", node.get("permission"));
        assertEquals(Boolean.TRUE, node.get("value"));
    }

    @Test
    @DisplayName("изменение прав применяется и сразу возвращает актуальное состояние")
    void appliesPermissionChange() throws Exception {
        bridge.install("LuckPerms");

        HttpResponse<String> response = post(
                "/players/" + FakeGameBridge.STEVE + "/permissions",
                TOKEN,
                "{\"kind\":\"group\",\"key\":\"vip\",\"value\":true}");

        assertEquals(200, response.statusCode());
        assertEquals(List.of("add:GROUP:vip=true"), bridge.permissionWrites);
        // В ответе — состояние прав, а не просто {"ok":true}.
        assertTrue(response.body().contains("primaryGroup"));
    }

    @Test
    @DisplayName("несуществующая группа отклоняется с 409 и причиной, а не молча")
    void rejectsUnknownGroup() throws Exception {
        bridge.install("LuckPerms");

        HttpResponse<String> response = post(
                "/players/" + FakeGameBridge.STEVE + "/permissions",
                TOKEN,
                "{\"kind\":\"group\",\"key\":\"nosuchgroup\"}");

        assertEquals(409, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        assertEquals("rejected", body.get("code"));
        assertTrue(((String) body.get("error")).contains("nosuchgroup"));
        assertTrue(bridge.permissionWrites.isEmpty());
    }

    @Test
    @DisplayName("кривой kind и кривой key отклоняются до обращения к игре")
    void validatesPermissionBody() throws Exception {
        bridge.install("LuckPerms");

        assertEquals(400, post("/players/" + FakeGameBridge.STEVE + "/permissions", TOKEN,
                "{\"kind\":\"wat\",\"key\":\"vip\"}").statusCode());
        // Пробел в ноде — верный признак, что кто-то передал не то.
        assertEquals(400, post("/players/" + FakeGameBridge.STEVE + "/permissions", TOKEN,
                "{\"kind\":\"permission\",\"key\":\"essentials fly\"}").statusCode());
        assertTrue(bridge.permissionWrites.isEmpty());
    }

    // ------------------------------------------- Инвентарь офлайн (InvSee++)

    @Test
    @DisplayName("офлайн-игрок без InvSee++ — понятный код, а не пустой 404")
    void offlineInventoryWithoutInvsee() throws Exception {
        bridge.steveOnline = false;

        HttpResponse<String> response = get("/players/" + FakeGameBridge.STEVE + "/inventory", TOKEN);

        assertEquals(404, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        assertEquals("offline-requires-invsee", body.get("code"));
    }

    @Test
    @DisplayName("с InvSee++ инвентарь офлайн-игрока читается")
    void offlineInventoryWithInvsee() throws Exception {
        bridge.steveOnline = false;
        bridge.install("InvSeePlusPlus");

        HttpResponse<String> response = get("/players/" + FakeGameBridge.STEVE + "/inventory", TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("minecraft:bread"));
    }

    @Test
    @DisplayName("если InvSee++ стоит, но данных нет — код другой")
    void offlineInventoryNoData() throws Exception {
        bridge.steveOnline = false;
        bridge.install("InvSeePlusPlus");
        String unknown = "00000000-0000-4000-8000-000000000000";

        HttpResponse<String> response = get("/players/" + unknown + "/inventory", TOKEN);

        assertEquals(404, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        assertEquals("offline-no-data", body.get("code"));
    }

    @Test
    @DisplayName("живой инвентарь по-прежнему идёт через Paper, InvSee++ не трогается")
    void onlineInventoryStillUsesPaper() throws Exception {
        bridge.install("InvSeePlusPlus");

        HttpResponse<String> response = get("/players/" + FakeGameBridge.STEVE + "/inventory", TOKEN);

        assertEquals(200, response.statusCode());
        // Хлеб отдаёт только офлайн-ветка; здесь должен быть живой инвентарь.
        assertFalse(response.body().contains("minecraft:bread"));
        assertTrue(response.body().contains("minecraft:diamond_sword"));
    }

    // ------------------------------------------------------- Экономика (Vault)

    private String balancePath() {
        return "/players/" + FakeGameBridge.STEVE + "/balance";
    }

    @Test
    @DisplayName("без Vault баланс отвечает кодом requires-vault")
    void balanceWithoutVault() throws Exception {
        HttpResponse<String> response = get(balancePath(), TOKEN);

        assertEquals(404, response.statusCode());
        assertEquals("requires-vault", JsonParser.parseObject(response.body()).get("code"));
    }

    @Test
    @DisplayName("Vault есть, а плагина экономики нет — код другой, no-provider")
    void balanceWithVaultButNoProvider() throws Exception {
        bridge.install("Vault");
        bridge.economyProvider = false;

        HttpResponse<String> response = get(balancePath(), TOKEN);

        assertEquals(404, response.statusCode());
        assertEquals("no-provider", JsonParser.parseObject(response.body()).get("code"));
    }

    @Test
    @DisplayName("с Vault баланс читается вместе с форматированной строкой и валютой")
    void balanceWithVault() throws Exception {
        bridge.install("Vault");

        HttpResponse<String> response = get(balancePath(), TOKEN);

        assertEquals(200, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        assertEquals(250.0, (Double) body.get("balance"));
        assertEquals("250.00 монет", body.get("formatted"));
        assertEquals("монет", body.get("currency"));
    }

    @Test
    @DisplayName("начисление отдаёт баланс до и после — именно их пишет аудит панели")
    void depositReturnsBeforeAndAfter() throws Exception {
        bridge.install("Vault");

        HttpResponse<String> response =
                post(balancePath() + "/deposit", TOKEN, "{\"amount\":50,\"reason\":\"компенсация\"}");

        assertEquals(200, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        assertEquals(Boolean.TRUE, body.get("ok"));
        assertEquals(250.0, (Double) body.get("balanceBefore"));
        assertEquals(300.0, (Double) body.get("balanceAfter"));
        assertEquals(300.0, bridge.balances.get(FakeGameBridge.STEVE));
    }

    @Test
    @DisplayName("списание больше баланса — 200 с ok:false и причиной от провайдера")
    void withdrawTooMuchIsRejectedByProvider() throws Exception {
        bridge.install("Vault");

        HttpResponse<String> response = post(balancePath() + "/withdraw", TOKEN, "{\"amount\":1000}");

        // Не 4xx: запрос корректен, отказал провайдер, и его текст нужен панели.
        assertEquals(200, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        assertEquals(Boolean.FALSE, body.get("ok"));
        assertEquals("Недостаточно средств", body.get("error"));
        assertEquals(250.0, bridge.balances.get(FakeGameBridge.STEVE));
    }

    @Test
    @DisplayName("сумма обязана быть положительным числом")
    void amountMustBePositiveNumber() throws Exception {
        bridge.install("Vault");

        // Отрицательная сумма в deposit означала бы списание — знак задаёт
        // только маршрут, иначе аудит покажет операцию наизнанку.
        assertEquals(400, post(balancePath() + "/deposit", TOKEN, "{\"amount\":-5}").statusCode());
        assertEquals(400, post(balancePath() + "/deposit", TOKEN, "{\"amount\":0}").statusCode());
        assertEquals(400, post(balancePath() + "/deposit", TOKEN, "{\"amount\":\"100\"}").statusCode());
        assertEquals(400, post(balancePath() + "/deposit", TOKEN, "").statusCode());
        assertEquals(250.0, bridge.balances.get(FakeGameBridge.STEVE));
    }

    @Test
    @DisplayName("сводка экономики: сумма по всем и доска богатства по убыванию")
    void economySummarySortsAndSums() throws Exception {
        bridge.install("Vault");
        java.util.UUID alex = java.util.UUID.fromString("11111111-2222-4333-8444-555555555555");
        bridge.balances.put(alex, 1000.0);
        bridge.playerNames.put(alex, "Alex");

        HttpResponse<String> response = get("/economy", TOKEN);

        assertEquals(200, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        assertEquals(1250.0, (Double) body.get("total"));
        assertEquals(2.0, (Double) body.get("playersCounted"));
        List<?> top = (List<?>) body.get("top");
        assertEquals(2, top.size());
        assertEquals("Alex", ((Map<?, ?>) top.get(0)).get("name"));
        assertEquals("Steve", ((Map<?, ?>) top.get(1)).get("name"));
    }

    @Test
    @DisplayName("параметр top ограничивает доску богатства")
    void economyTopLimit() throws Exception {
        bridge.install("Vault");
        bridge.balances.put(java.util.UUID.randomUUID(), 10.0);
        bridge.balances.put(java.util.UUID.randomUUID(), 20.0);

        Map<String, Object> body = JsonParser.parseObject(get("/economy?top=1", TOKEN).body());

        assertEquals(1, ((List<?>) body.get("top")).size());
        // Сумма считается по всем, а не по обрезанной доске.
        assertEquals(280.0, (Double) body.get("total"));
        assertEquals(400, get("/economy?top=abc", TOKEN).statusCode());
    }

    @Test
    @DisplayName("экономика сервера без Vault — тот же понятный код")
    void economyWithoutVault() throws Exception {
        HttpResponse<String> response = get("/economy", TOKEN);

        assertEquals(404, response.statusCode());
        assertEquals("requires-vault", JsonParser.parseObject(response.body()).get("code"));
    }
    // ------------------------------------------- Горячее переключение плагинов

    @Test
    @DisplayName("плагин выключается и включается без перезапуска")
    void togglePlugin() throws Exception {
        bridge.install("LuckPerms");

        HttpResponse<String> off = post("/plugins/LuckPerms/enabled", TOKEN, "{\"enabled\":false}");
        assertEquals(200, off.statusCode());
        assertEquals(Boolean.FALSE, JsonParser.parseObject(off.body()).get("enabled"));
        assertFalse(bridge.installedPlugins().stream()
                .filter(p -> p.name().equals("LuckPerms"))
                .findFirst()
                .orElseThrow()
                .enabled());

        HttpResponse<String> on = post("/plugins/LuckPerms/enabled", TOKEN, "{\"enabled\":true}");
        assertEquals(200, on.statusCode());
        assertEquals(Boolean.TRUE, JsonParser.parseObject(on.body()).get("enabled"));
    }

    @Test
    @DisplayName("сам companion выключить нельзя — иначе панель потеряет связь")
    void cannotDisableSelf() throws Exception {
        HttpResponse<String> response =
                post("/plugins/AurumCompanion/enabled", TOKEN, "{\"enabled\":false}");

        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("companion"));
    }

    @Test
    @DisplayName("неизвестный плагин — отказ с причиной, а не тихий успех")
    void unknownPluginRejected() throws Exception {
        HttpResponse<String> response = post("/plugins/НетТакого/enabled", TOKEN, "{\"enabled\":true}");

        assertEquals(409, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        assertEquals("toggle-failed", body.get("code"));
    }

    @Test
    @DisplayName("плагин, упавший при переключении, не роняет сервер")
    void stubbornPluginReported() throws Exception {
        bridge.install("Broken");
        bridge.stubborn.add("Broken");

        HttpResponse<String> response = post("/plugins/Broken/enabled", TOKEN, "{\"enabled\":false}");

        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("отказался переключиться"));
    }

    @Test
    @DisplayName("поле enabled обязательно: молчание не значит «выключить»")
    void enabledFieldRequired() throws Exception {
        bridge.install("LuckPerms");

        assertEquals(400, post("/plugins/LuckPerms/enabled", TOKEN, "{}").statusCode());
        assertEquals(400, post("/plugins/LuckPerms/enabled", TOKEN, "").statusCode());
        assertEquals(400, post("/plugins/LuckPerms/enabled", TOKEN, "{\"enabled\":\"yes\"}").statusCode());
    }

    // ------------------------------------------------- одноразовый код входа

    @Test
    void кодОбмениваетсяНаИгрока() throws Exception {
        String code = webTokens.issue(FakeGameBridge.STEVE, "Steve", java.time.Instant.now());

        HttpResponse<String> response = post("/webtoken/" + code, TOKEN, "");
        assertEquals(200, response.statusCode());
        Map<?, ?> body = (Map<?, ?>) JsonParser.parse(response.body());
        assertEquals(FakeGameBridge.STEVE.toString(), body.get("uuid"));
        assertEquals("Steve", body.get("name"));
        assertFalse(response.body().contains(code), "израсходованный код незачем повторять в ответе");
    }

    @Test
    void повторныйОбменТогоЖеКодаНеПроходит() throws Exception {
        String code = webTokens.issue(FakeGameBridge.STEVE, "Steve", java.time.Instant.now());
        assertEquals(200, post("/webtoken/" + code, TOKEN, "").statusCode());
        assertEquals(404, post("/webtoken/" + code, TOKEN, "").statusCode());
    }

    @Test
    void несуществующийКодОтвечаетТемЖе404() throws Exception {
        // Тот же ответ, что и на использованный код: по разнице между ними
        // подбор стал бы заметно осмысленнее.
        assertEquals(404, post("/webtoken/ZZZZZZZZ", TOKEN, "").statusCode());
    }

    @Test
    void обменКодаТребуетТокен() throws Exception {
        String code = webTokens.issue(FakeGameBridge.STEVE, "Steve", java.time.Instant.now());
        assertEquals(401, post("/webtoken/" + code, null, "").statusCode());
    }

    // ------------------------------------------------------- сброс пароля

    @Test
    void сбросВозвращаетТокенИСрок() throws Exception {
        bridge.resetToIssue = new ovh.aurumgg.companion.core.model.PasswordReset(
                "Steve", "ABCD2345", 1_800_000_000_000L);

        HttpResponse<String> response = post("/auth/reset/Steve", TOKEN, "");
        assertEquals(200, response.statusCode());
        Map<?, ?> body = (Map<?, ?>) JsonParser.parse(response.body());
        assertEquals("Steve", body.get("username"));
        assertEquals("ABCD2345", body.get("token"));
        assertEquals(java.util.List.of("Steve"), bridge.resetRequests);
    }

    @Test
    void сбросБезПлагинаАвторизацииОтвечаетПонятнымКодом() throws Exception {
        bridge.resetToIssue = null;
        HttpResponse<String> response = post("/auth/reset/Steve", TOKEN, "");
        assertEquals(404, response.statusCode());
        Map<?, ?> body = (Map<?, ?>) JsonParser.parse(response.body());
        assertEquals("reset-unavailable", body.get("code"));
    }

    @Test
    void несуществующийНикОтвечаетТемЖе404() throws Exception {
        // Тот же ответ, что и «плагина нет»: различать их значит помогать
        // перебирать ники.
        bridge.resetToIssue = null;
        assertEquals(404, post("/auth/reset/НетТакого", TOKEN, "").statusCode());
    }

    @Test
    void сбросТребуетТокен() throws Exception {
        bridge.resetToIssue = new ovh.aurumgg.companion.core.model.PasswordReset(
                "Steve", "ABCD2345", 1_800_000_000_000L);
        assertEquals(401, post("/auth/reset/Steve", null, "").statusCode());
        assertTrue(bridge.resetRequests.isEmpty(), "без токена запрос не должен доходить до плагина");
    }

    // ------------------------------------------------------------ гильдии

    private void withGuild() {
        bridge.guildsInstalled = true;
        bridge.guilds.add(new ovh.aurumgg.companion.core.model.GuildInfo(
                7, "Драконы", "DRG", "11111111-1111-1111-1111-111111111111", "Лидер",
                3, 1200, 1_700_000_000_000L,
                List.of(new ovh.aurumgg.companion.core.model.GuildInfo.Member(
                        "11111111-1111-1111-1111-111111111111", "Лидер", "leader",
                        1_700_000_000_000L))));
    }

    @Test
    @DisplayName("Без плагина гильдий раздел отвечает 503, а не 404")
    void guildsUnavailable() throws Exception {
        // 404 панель истолковала бы как «гильдий нет» и показала бы пустой
        // список вместо объяснения, почему раздел не работает.
        HttpResponse<String> response = get("/guilds", TOKEN);
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("guilds-unavailable"), response.body());
    }

    @Test
    @DisplayName("Список гильдий и поиск по имени")
    void guildList() throws Exception {
        withGuild();

        Map<String, Object> all = JsonParser.parseObject(get("/guilds", TOKEN).body());
        assertEquals(1, ((List<?>) all.get("guilds")).size());

        assertEquals(1, ((List<?>) JsonParser.parseObject(
                get("/guilds?query=дра", TOKEN).body()).get("guilds")).size());
        assertEquals(0, ((List<?>) JsonParser.parseObject(
                get("/guilds?query=пусто", TOKEN).body()).get("guilds")).size());
    }

    @Test
    @DisplayName("Карточка гильдии отдаёт весь состав")
    void guildDetail() throws Exception {
        withGuild();

        HttpResponse<String> response = get("/guilds/7", TOKEN);
        assertEquals(200, response.statusCode());
        Map<String, Object> body = JsonParser.parseObject(response.body());
        assertEquals("Драконы", body.get("name"));
        assertEquals(1, ((List<?>) body.get("members")).size());

        assertEquals(404, get("/guilds/999", TOKEN).statusCode());
    }

    @Test
    @DisplayName("Игрок без гильдии — это 200 с пустым полем, а не 404")
    void membershipOfPlayerWithoutGuild() throws Exception {
        // «Не состоит в гильдии» — обычное состояние игрока, а не отсутствие
        // ресурса, и 404 заставил бы панель показывать ошибку каждому второму.
        bridge.guildsInstalled = true;
        HttpResponse<String> response =
                get("/players/11111111-1111-1111-1111-111111111111/guild", TOKEN);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("null"), response.body());
    }

    @Test
    @DisplayName("Административные действия доходят до плагина вместе с исполнителем")
    void adminActions() throws Exception {
        withGuild();

        assertEquals(200, post("/guilds/7/disband", TOKEN, "{\"actor\":\"ГМ\"}").statusCode());
        assertEquals(200,
                post("/guilds/7/transfer", TOKEN, "{\"actor\":\"ГМ\",\"target\":\"Стив\"}").statusCode());
        assertEquals(200,
                post("/guilds/members/%D0%A1%D1%82%D0%B8%D0%B2/remove", TOKEN, "{\"actor\":\"ГМ\"}")
                        .statusCode());

        assertEquals(
                List.of("disband 7 ГМ", "transfer 7 Стив ГМ", "remove Стив ГМ"),
                bridge.guildActions);
    }

    @Test
    @DisplayName("Передача лидерства без игрока — 400, а отказ плагина — 409")
    void adminActionErrors() throws Exception {
        withGuild();

        assertEquals(400, post("/guilds/7/transfer", TOKEN, "{\"actor\":\"ГМ\"}").statusCode());

        bridge.guildOutcome =
                new ovh.aurumgg.companion.core.model.GuildActionOutcome(false, "Такой гильдии нет");
        // 409, а не 400: запрос корректен, отказало состояние игрового сервера.
        HttpResponse<String> response = post("/guilds/7/disband", TOKEN, "{\"actor\":\"ГМ\"}");
        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("Такой гильдии нет"), response.body());
    }

    @Test
    @DisplayName("Действия с гильдиями тоже закрыты токеном")
    void guildActionsNeedToken() throws Exception {
        withGuild();
        assertEquals(401, get("/guilds", null).statusCode());
        assertEquals(401, post("/guilds/7/disband", null, "{}").statusCode());
    }
}
