using System;
using System.Collections.Generic;
using System.Linq;
using Aurum.Companion.Core;
using Aurum.Companion.Core.Game;

namespace Aurum.Companion.Core.Tests;

/// <summary>
/// Подставная игра.
/// </summary>
/// <remarks>
/// Существует ровно потому, что ядро не знает про 7 Days to Die: весь код,
/// который стоит проверять, отделён от Assembly-CSharp.dll, и здесь ему
/// подсовывается запоминающая заглушка вместо мира.
/// </remarks>
public sealed class FakeGameBridge : IGameBridge
{
    public List<(string PlayerId, string Text)> PrivateMessages { get; } = new();
    public List<string> Broadcasts { get; } = new();
    public List<string> Logs { get; } = new();
    public List<string> Errors { get; } = new();
    public List<OnlinePlayer> Players { get; } = new();
    public WorldState World { get; set; } = new();

    /// <summary>Кого считать вышедшим: личное сообщение такому не доходит.</summary>
    public HashSet<string> Offline { get; } = new(StringComparer.Ordinal);

    public bool SendPrivateMessage(string playerId, string text)
    {
        if (Offline.Contains(playerId)) return false;
        PrivateMessages.Add((playerId, text));
        return true;
    }

    public void Broadcast(string text) => Broadcasts.Add(text);

    public IReadOnlyList<OnlinePlayer> OnlinePlayers() => Players;

    public OnlinePlayer? FindPlayer(string idOrName) =>
        Players.FirstOrDefault(p =>
            string.Equals(p.PlayerId, idOrName, StringComparison.Ordinal) ||
            string.Equals(p.Name, idOrName, StringComparison.OrdinalIgnoreCase));

    public WorldState ReadWorldState() => World;

    public void Log(string message) => Logs.Add(message);

    public void LogError(string message, Exception? error) => Errors.Add(message);

    /// <summary>Последнее, что сказали игроку. Пусто, если не говорили ничего.</summary>
    public string LastMessageTo(string playerId) =>
        PrivateMessages.LastOrDefault(m => m.PlayerId == playerId).Text ?? "";
}

/// <summary>Выполняет отложенную работу на месте — тесту не нужен настоящий поток.</summary>
public sealed class InlineDispatcher : Aurum.Companion.Core.Tickets.IWorkDispatcher
{
    public void Run(Action work) => work();
}

/// <summary>Подставной транспорт: отвечает по сценарию и запоминает, что ушло.</summary>
public sealed class FakeTransport : Aurum.Companion.Core.Panel.IHttpTransport
{
    private readonly Queue<Aurum.Companion.Core.Panel.PanelResponse> _responses = new();

    public List<(string Url, string Body, string Token)> Calls { get; } = new();

    public FakeTransport Respond(int status, string body = "{}")
    {
        _responses.Enqueue(new Aurum.Companion.Core.Panel.PanelResponse(status, body));
        return this;
    }

    public Aurum.Companion.Core.Panel.PanelResponse Post(string url, string jsonBody, string token)
    {
        Calls.Add((url, jsonBody, token));
        return _responses.Count > 0
            ? _responses.Dequeue()
            : new Aurum.Companion.Core.Panel.PanelResponse(200, "{\"created\":true,\"ticketId\":\"t-1\"}");
    }
}
