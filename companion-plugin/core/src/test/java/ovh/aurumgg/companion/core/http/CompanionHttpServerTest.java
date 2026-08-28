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
}
