using System.Collections.Generic;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Json;

namespace Aurum.Companion.Core.Panel
{
    /// <summary>
    /// Тела запросов к панели.
    /// </summary>
    /// <remarks>
    /// Сборка тела вынесена сюда и покрыта тестами отдельно от сети: сетевой
    /// код в тестах приходится подменять, а формат — это контракт с панелью,
    /// и ломаться он должен на сборке, а не на живом сервере.
    /// </remarks>
    public static class PanelPayloads
    {
        public static string Ticket(string playerId, string playerName, string text) =>
            JsonWriter.Object(new[]
            {
                Field("playerId", JsonWriter.String(playerId)),
                Field("playerName", JsonWriter.String(playerName)),
                Field("text", JsonWriter.String(text)),
            });

        public static string Report(string reporterId, string reporterName, string accusedName, string reason, OnlinePlayer? where) =>
            JsonWriter.Object(new[]
            {
                Field("playerId", JsonWriter.String(reporterId)),
                Field("playerName", JsonWriter.String(reporterName)),
                Field("accusedName", JsonWriter.String(accusedName)),
                Field("reason", JsonWriter.String(reason)),
                // Координаты жалующегося, а не обвиняемого: это место, где
                // произошло то, на что жалуются.
                Field("x", where == null ? "null" : JsonWriter.Coordinate(where.X)),
                Field("y", where == null ? "null" : JsonWriter.Coordinate(where.Y)),
                Field("z", where == null ? "null" : JsonWriter.Coordinate(where.Z)),
            });

        public static string Event(GameEvent e) =>
            JsonWriter.Object(new[]
            {
                Field("kind", JsonWriter.String(KindName(e.Kind))),
                Field("playerId", JsonWriter.String(e.PlayerId)),
                Field("playerName", JsonWriter.String(e.PlayerName)),
                Field("occurredAt", JsonWriter.Timestamp(e.OccurredAt)),
                Field("text", JsonWriter.String(e.Text)),
                Field("actorId", JsonWriter.String(e.ActorId)),
                Field("actorName", JsonWriter.String(e.ActorName)),
                Field("x", JsonWriter.Coordinate(e.X)),
                Field("y", JsonWriter.Coordinate(e.Y)),
                Field("z", JsonWriter.Coordinate(e.Z)),
            });

        /// <summary>Пачка событий одним запросом: по одному было бы слишком много походов.</summary>
        public static string EventBatch(IEnumerable<GameEvent> events)
        {
            var items = new List<string>();
            foreach (var e in events) items.Add(Event(e));
            return JsonWriter.Object(new[]
            {
                Field("events", JsonWriter.Array(items)),
            });
        }

        /// <summary>Имена событий — часть контракта с панелью, поэтому заданы явно, а не ToString().</summary>
        public static string KindName(GameEventKind kind)
        {
            switch (kind)
            {
                case GameEventKind.Chat: return "chat";
                case GameEventKind.Join: return "join";
                case GameEventKind.Leave: return "leave";
                case GameEventKind.Death: return "death";
                case GameEventKind.PlayerKill: return "player-kill";
                default: return "unknown";
            }
        }

        private static KeyValuePair<string, string> Field(string key, string value) =>
            new KeyValuePair<string, string>(key, value);
    }
}
