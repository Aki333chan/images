using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Http;
using Aurum.Companion.Core.Json;
using Xunit;

namespace Aurum.Companion.Core.Tests;

/// <summary>
/// Входящее направление: панель → мод.
///
/// Роутер отделён от сокета ровно ради этих тестов — проверки, маршруты и
/// ответы проходят без единого открытого порта.
/// </summary>
public class RouterTests
{
    private const string Token = "токен-длиннее-шестнадцати";

    private static (CompanionRouter Router, FakeGameBridge Game) Make()
    {
        var game = new FakeGameBridge();
        game.Players.Add(new OnlinePlayer(171, "Steam_76561198025499751", "Lost")
        {
            Level = 12, Health = 112, Ping = 13, X = 342.4f, Y = 49f, Z = -541.9f,
        });
        return (new CompanionRouter(game, Token, "1.0.0"), game);
    }

    private static HttpRequestData Get(string path) => new("GET", path, "");
    private static HttpRequestData Post(string path, string body) => new("POST", path, body);

    [Fact]
    public void Без_токена_ничего_не_отдаётся()
    {
        var (router, _) = Make();
        foreach (string? header in new[] { null, "", "Bearer не тот", "совсем не тот" })
        {
            var response = router.Handle(Get("/state"), header);
            Assert.Equal(401, response.Status);
            // Ни намёка на то, чем именно не подошёл токен.
            Assert.DoesNotContain(Token, response.Json);
        }
    }

    [Fact]
    public void Токен_принимается_и_со_схемой_и_без()
    {
        var (router, _) = Make();
        Assert.Equal(200, router.Handle(Get("/ping"), "Bearer " + Token).Status);
        Assert.Equal(200, router.Handle(Get("/ping"), Token).Status);
    }

    [Fact]
    public void Ping_называет_версию_контракта()
    {
        var (router, _) = Make();
        var map = JsonReader.ParseObject(router.Handle(Get("/ping"), Token).Json);
        Assert.Equal("aurum-companion", JsonReader.StringOrNull(map, "mod"));
        Assert.Equal("1", JsonReader.StringOrNull(map, "contract"));
    }

    /// <remarks>
    /// Ради этого поля мод и нужен панели: без него она вынуждена считать
    /// «день кратен семи», хотя частота орды настраивается.
    /// </remarks>
    [Fact]
    public void Состояние_отдаёт_кровавую_луну_фактом_а_не_расчётом()
    {
        var (router, game) = Make();
        game.World = new WorldState
        {
            Day = 12, Hour = 19, Minute = 53,
            IsBloodMoonActive = true, BloodMoonFrequency = 10,
            Fps = 57.25f, Zombies = 40, MaxZombies = 64, OnlinePlayers = 1, MaxPlayers = 8,
            Version = "V 2.0 (b28)",
        };

        var map = JsonReader.ParseObject(router.Handle(Get("/state"), Token).Json);
        Assert.True(JsonReader.BoolOrDefault(map, "bloodMoonActive"));
        Assert.Equal("V 2.0 (b28)", JsonReader.StringOrNull(map, "version"));
        Assert.Contains("\"bloodMoonFrequency\":10", router.Handle(Get("/state"), Token).Json);
        Assert.Contains("\"fps\":57.25", router.Handle(Get("/state"), Token).Json);
    }

    [Fact]
    public void Неизвестная_частота_орды_это_null_а_не_ноль()
    {
        var (router, game) = Make();
        game.World = new WorldState { BloodMoonFrequency = -1 };
        Assert.Contains("\"bloodMoonFrequency\":null", router.Handle(Get("/state"), Token).Json);
    }

    [Fact]
    public void Список_игроков_с_координатами_без_мусора()
    {
        var (router, _) = Make();
        string json = router.Handle(Get("/players"), Token).Json;
        Assert.Contains("\"playerId\":\"Steam_76561198025499751\"", json);
        Assert.Contains("\"x\":342.4", json);
        Assert.Contains("\"z\":-541.9", json);
    }

    [Fact]
    public void Ответ_модератора_доходит_до_игрока()
    {
        var (router, game) = Make();
        var response = router.Handle(
            Post("/players/Steam_76561198025499751/message", "{\"text\":\"Вещи вернули, извините\"}"),
            Token);

        Assert.Equal(200, response.Status);
        Assert.Contains("\"delivered\":true", response.Json);
        Assert.Equal("Вещи вернули, извините", game.LastMessageTo("Steam_76561198025499751"));
    }

    /// <remarks>
    /// Модератор отвечает, когда удобно ему, а не когда игрок в сети. Панель
    /// должна отличать «не доставлено» от «запрос неверный», поэтому 200 с
    /// флагом, а не 404.
    /// </remarks>
    [Fact]
    public void Офлайн_игрок_это_не_ошибка_а_флаг()
    {
        var (router, game) = Make();
        game.Offline.Add("Steam_76561198025499751");

        var response = router.Handle(
            Post("/players/Steam_76561198025499751/message", "{\"text\":\"ответ\"}"),
            Token);

        Assert.Equal(200, response.Status);
        Assert.Contains("\"delivered\":false", response.Json);
    }

    [Fact]
    public void Идентификатор_из_пути_раскодируется()
    {
        var (router, game) = Make();
        router.Handle(Post("/players/Steam_7656%20119/message", "{\"text\":\"привет\"}"), Token);
        Assert.Equal("Steam_7656 119", game.PrivateMessages[0].PlayerId);
    }

    // Перевод строки в ответе подделал бы чужую строку журнала сервера.
    [Fact]
    public void Управляющие_символы_из_ответа_вычищаются()
    {
        var (router, game) = Make();
        router.Handle(Post("/players/Steam_1/message", "{\"text\":\"строка\\nвторая\"}"), Token);
        Assert.Equal("строка вторая", game.LastMessageTo("Steam_1"));
    }

    [Fact]
    public void Пустое_сообщение_отклоняется()
    {
        var (router, game) = Make();
        Assert.Equal(400, router.Handle(Post("/players/Steam_1/message", "{\"text\":\"   \"}"), Token).Status);
        Assert.Empty(game.PrivateMessages);
    }

    [Fact]
    public void Битое_тело_это_400_а_не_500()
    {
        var (router, _) = Make();
        var response = router.Handle(Post("/players/Steam_1/message", "не json"), Token);
        Assert.Equal(400, response.Status);
    }

    [Fact]
    public void Неизвестный_маршрут_это_404()
    {
        var (router, _) = Make();
        Assert.Equal(404, router.Handle(Get("/inventory"), Token).Status);
        Assert.Equal(404, router.Handle(Post("/state", "{}"), Token).Status);
    }

    [Fact]
    public void Объявление_уходит_всем()
    {
        var (router, game) = Make();
        Assert.Equal(200, router.Handle(Post("/broadcast", "{\"text\":\"Рестарт через 5 минут\"}"), Token).Status);
        Assert.Equal("Рестарт через 5 минут", Assert.Single(game.Broadcasts));
    }
}
