using System;
using Aurum.Companion.Core;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Panel;
using Aurum.Companion.Core.Tickets;
using Xunit;

namespace Aurum.Companion.Core.Tests;

/// <summary>
/// Путь обращения целиком: чат → проверки → панель → ответ игроку.
///
/// Проверяется именно то, что видит человек в игре, и то, что уходит в
/// панель, — а не внутреннее устройство.
/// </summary>
public class TicketServiceTests
{
    private static CompanionConfig Config() => CompanionConfig.Parse(new[]
    {
        "panel-url = http://10.0.0.1:3001",
        "server-id = srv-1",
        "token = токен-длиннее-шестнадцати",
    });

    private sealed class Harness
    {
        public FakeGameBridge Game { get; } = new();
        public FakeTransport Transport { get; } = new();
        public DateTimeOffset Now { get; set; } = new(2026, 8, 23, 12, 0, 0, TimeSpan.Zero);
        public TicketService Service { get; }
        public OnlinePlayer Player { get; }

        public Harness(int cooldownSeconds = 60)
        {
            Player = new OnlinePlayer(171, "Steam_76561198025499751", "Lost") { X = 342.4f, Y = 49f, Z = -541.9f };
            Game.Players.Add(Player);
            Service = new TicketService(
                new PanelClient(Config(), Transport),
                Game,
                new TicketCooldown(cooldownSeconds),
                new InlineDispatcher(),
                () => Now);
        }

        public bool Send(string message) => Service.Handle(Player, ChatCommand.Parse(message));
    }

    [Fact]
    public void Обращение_уходит_в_панель_и_игрок_получает_подтверждение()
    {
        var h = new Harness();
        h.Transport.Respond(200, "{\"created\":true,\"ticketId\":\"t-1\"}");

        bool swallowed = h.Send("/ticket пропали вещи после смерти");

        Assert.True(swallowed);
        var call = Assert.Single(h.Transport.Calls);
        Assert.Equal("http://10.0.0.1:3001/api/internal/sevendays/servers/srv-1/tickets", call.Url);
        Assert.Contains("\"playerId\":\"Steam_76561198025499751\"", call.Body);
        Assert.Contains("пропали вещи после смерти", call.Body);
        Assert.Equal("токен-длиннее-шестнадцати", call.Token);
        Assert.Contains("Обращение отправлено", h.Game.LastMessageTo(h.Player.PlayerId));
    }

    // Мод обязан проглотить сообщение: жалобу не должен увидеть тот, на кого жалуются.
    [Fact]
    public void Команда_не_попадает_в_общий_чат()
    {
        var h = new Harness();
        Assert.True(h.Send("/report Гриферша ломает базу"));
        Assert.Empty(h.Game.Broadcasts);
    }

    [Fact]
    public void Обычное_сообщение_мод_пропускает_дальше()
    {
        var h = new Harness();
        Assert.False(h.Send("привет всем"));
        Assert.Empty(h.Transport.Calls);
    }

    [Fact]
    public void Жалоба_уходит_своим_роутом_с_координатами_жалующегося()
    {
        var h = new Harness();
        h.Send("/report Гриферша ломает чужую базу");

        var call = Assert.Single(h.Transport.Calls);
        Assert.EndsWith("/reports", call.Url);
        Assert.Contains("\"accusedName\":\"Гриферша\"", call.Body);
        Assert.Contains("\"reason\":\"ломает чужую базу\"", call.Body);
        // Место, где произошло то, на что жалуются.
        Assert.Contains("\"x\":342.4", call.Body);
        Assert.Contains("\"z\":-541.9", call.Body);
    }

    [Fact]
    public void Повтор_раньше_срока_отклоняется_с_указанием_времени()
    {
        var h = new Harness(cooldownSeconds: 60);
        h.Send("/ticket первое обращение");
        Assert.Single(h.Transport.Calls);

        h.Now = h.Now.AddSeconds(20);
        h.Send("/ticket второе обращение");

        // Второе до панели не дошло вовсе — в этом весь смысл проверки в моде.
        Assert.Single(h.Transport.Calls);
        Assert.Contains("через 40", h.Game.LastMessageTo(h.Player.PlayerId));
    }

    [Fact]
    public void После_истечения_срока_обращение_снова_проходит()
    {
        var h = new Harness(cooldownSeconds: 60);
        h.Send("/ticket первое обращение");
        h.Now = h.Now.AddSeconds(61);
        h.Send("/ticket второе обращение");

        Assert.Equal(2, h.Transport.Calls.Count);
    }

    // Заставлять человека ждать минуту из-за нашей неудачи несправедливо.
    [Fact]
    public void Неудачная_отправка_не_расходует_ожидание()
    {
        var h = new Harness(cooldownSeconds: 60);
        h.Transport.Respond(0, "");
        h.Send("/ticket панель лежит");
        Assert.Contains("панель недоступна", h.Game.LastMessageTo(h.Player.PlayerId));

        h.Transport.Respond(200, "{\"created\":true}");
        h.Now = h.Now.AddSeconds(1);
        h.Send("/ticket пробую снова");

        Assert.Equal(2, h.Transport.Calls.Count);
        Assert.Contains("Обращение отправлено", h.Game.LastMessageTo(h.Player.PlayerId));
    }

    [Fact]
    public void Дописывание_к_открытому_обращению_называется_своими_словами()
    {
        var h = new Harness();
        h.Transport.Respond(200, "{\"created\":false,\"ticketId\":\"t-1\"}");
        h.Send("/ticket ещё подробность");
        Assert.Contains("Дописано", h.Game.LastMessageTo(h.Player.PlayerId));
    }

    [Fact]
    public void Отказ_панели_объясняется_человеческими_словами()
    {
        var h = new Harness();
        h.Transport.Respond(403, "{\"message\":\"Неверный токен сервера\"}");
        h.Send("/ticket проверка");

        string said = h.Game.LastMessageTo(h.Player.PlayerId);
        Assert.Contains("не авторизован", said);
        // Ни кода, ни внутренностей панели игроку не показываем.
        Assert.DoesNotContain("403", said);
    }

    [Fact]
    public void Пустое_обращение_объясняет_как_надо_и_в_панель_не_идёт()
    {
        var h = new Harness();
        Assert.True(h.Send("/ticket"));
        Assert.Empty(h.Transport.Calls);
        Assert.Contains("Напишите, что случилось", h.Game.LastMessageTo(h.Player.PlayerId));
    }

    [Fact]
    public void Игрок_вышедший_до_ответа_не_роняет_мод()
    {
        var h = new Harness();
        h.Game.Offline.Add(h.Player.PlayerId);
        var ex = Record.Exception(() => h.Send("/ticket проверка"));
        Assert.Null(ex);
        Assert.Single(h.Transport.Calls);
    }
}
