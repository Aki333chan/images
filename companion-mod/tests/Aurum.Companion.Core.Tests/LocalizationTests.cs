using System;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Http;
using Aurum.Companion.Core.Json;
using Aurum.Companion.Core.Panel;
using Aurum.Companion.Core.Tickets;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class LocalizationTests
{
    [Theory]
    [InlineData("en", "Ticket sent", "Describe the problem")]
    [InlineData("ru", "Обращение отправлено", "Напишите, что случилось")]
    [InlineData("pl", "Zgłoszenie wysłane", "Opisz problem")]
    public void Config_controls_success_validation_and_failures(string language, string success, string usage)
    {
        var config = CompanionConfig.Parse(new[] { "language = " + language });
        var game = new FakeGameBridge();
        var transport = new FakeTransport();
        var panel = new PanelClient(config, transport);
        var service = new TicketService(panel, game, new TicketCooldown(0), new InlineDispatcher());
        var player = new OnlinePlayer(1, "Steam_test", "Tester");
        service.Handle(player, ChatCommand.Parse("/ticket"));
        Assert.Contains(usage, game.LastMessageTo(player.PlayerId));
        Assert.Empty(transport.Calls);
        transport.Respond(200, "{\"created\":true}");
        service.Handle(player, ChatCommand.Parse("/ticket test"));
        Assert.Contains(success, game.LastMessageTo(player.PlayerId));
        transport.Respond(400, "{\"message\":\"internal-secret\"}");
        service.Handle(player, ChatCommand.Parse("/ticket test"));
        Assert.Contains(panel.Messages.Get(MessageKey.PanelRejected), game.LastMessageTo(player.PlayerId));
        Assert.DoesNotContain("internal-secret", game.LastMessageTo(player.PlayerId));
        Assert.Empty(game.Broadcasts);
    }

    [Theory]
    [InlineData("en")]
    [InlineData("ru")]
    [InlineData("pl")]
    public void Every_message_exists_and_formats(string language)
    {
        var messages = new Messages(language);
        foreach (MessageKey key in Enum.GetValues(typeof(MessageKey)))
        {
            string value = messages.Get(key, 500);
            if (key != MessageKey.None) Assert.False(string.IsNullOrWhiteSpace(value));
            Assert.DoesNotContain("{0}", value);
        }
    }

    [Theory]
    [InlineData("language = RU", "ru")]
    [InlineData("language = de", "en")]
    [InlineData("", "en")]
    public void Language_defaults_and_normalization(string line, string expected) =>
        Assert.Equal(expected, CompanionConfig.Parse(new[] { line }).Language);

    [Fact]
    public void Ping_declares_only_enabled_event_features_and_language()
    {
        var config = CompanionConfig.Parse(new[] { "language = pl", "forward-chat = false", "forward-deaths = false" });
        var router = new CompanionRouter(new FakeGameBridge(), "test", "1.0.3-rc.1", config);
        var response = router.Handle(new HttpRequestData("GET", "/ping", ""), "test");
        var map = JsonReader.ParseObject(response.Json);
        Assert.Equal("pl", JsonReader.StringOrNull(map, "language"));
        Assert.Contains("\"private-messages\"", response.Json);
        Assert.Contains("\"tickets\"", response.Json);
        Assert.DoesNotContain("\"chat-events\"", response.Json);
        Assert.DoesNotContain("\"death-events\"", response.Json);
        Assert.Equal(401, router.Handle(new HttpRequestData("GET", "/ping", ""), "wrong").Status);
    }
}
