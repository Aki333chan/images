using System;
using System.Collections.Generic;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Json;

namespace Aurum.Companion.Core.Panel
{
    /// <summary>Что панель ответила на тикет.</summary>
    public sealed class TicketResult
    {
        public TicketResult(bool ok, bool created, string? ticketId, string? error)
        {
            Ok = ok;
            Created = created;
            TicketId = ticketId;
            Error = error;
        }

        public bool Ok { get; }

        /// <summary>true — заведён новый тикет, false — дописано в открытый.</summary>
        public bool Created { get; }

        public string? TicketId { get; }
        public string? Error { get; }
    }

    /// <summary>
    /// Исходящее направление: мод → панель.
    /// </summary>
    /// <remarks>
    /// Адреса собираются из panel-url и server-id — то есть внутренние, по
    /// туннелю (10.0.0.2 → 10.0.0.1). Публичный домен здесь не используется:
    /// трафик не должен покидать туннель, а токен уходит заголовком.
    /// </remarks>
    public sealed class PanelClient
    {
        private readonly CompanionConfig _config;
        private readonly IHttpTransport _transport;

        public PanelClient(CompanionConfig config, IHttpTransport transport)
        {
            _config = config;
            _transport = transport;
        }

        private string Base => _config.PanelBaseUrl + "/api/internal/sevendays/servers/" + _config.PanelServerId;

        public TicketResult SendTicket(string playerId, string playerName, string text)
        {
            var response = _transport.Post(Base + "/tickets", PanelPayloads.Ticket(playerId, playerName, text), _config.Token);
            return ReadTicketResult(response);
        }

        public TicketResult SendReport(string reporterId, string reporterName, string accusedName, string reason, OnlinePlayer? where)
        {
            var body = PanelPayloads.Report(reporterId, reporterName, accusedName, reason, where);
            var response = _transport.Post(Base + "/reports", body, _config.Token);
            return ReadTicketResult(response);
        }

        /// <summary>Пачка событий. true — панель приняла; повтор решает вызывающий.</summary>
        public PanelResponse SendEvents(IReadOnlyList<GameEvent> events) =>
            _transport.Post(Base + "/events", PanelPayloads.EventBatch(events), _config.Token);

        private static TicketResult ReadTicketResult(PanelResponse response)
        {
            if (!response.IsSuccess)
            {
                return new TicketResult(false, false, null, DescribeFailure(response));
            }

            try
            {
                var map = JsonReader.ParseObject(response.Body);
                return new TicketResult(
                    true,
                    JsonReader.BoolOrDefault(map, "created"),
                    JsonReader.StringOrNull(map, "ticketId"),
                    null);
            }
            catch (JsonReader.JsonException)
            {
                // Панель ответила успехом, но телом, которого мы не понимаем.
                // Считаем принятым: сообщение игрока дошло, а как оно там
                // легло — забота панели, и терять его из-за формата нельзя.
                return new TicketResult(true, true, null, null);
            }
        }

        /// <summary>
        /// Причина отказа для игрока.
        /// </summary>
        /// <remarks>
        /// Игроку в чат уходит человеческий текст, а не код и не тело ответа:
        /// внутренние подробности панели ему ни к чему, а иногда и вредны.
        /// </remarks>
        private static string DescribeFailure(PanelResponse response)
        {
            if (response.Status == 0) return "панель недоступна";
            if (response.Status == 401 || response.Status == 403) return "сервер не авторизован в панели";
            if (response.Status == 429) return "слишком часто, попробуйте позже";
            if (response.Status >= 500) return "панель отвечает ошибкой";

            // 4xx: у панели есть внятная причина — покажем её, если она текстом.
            try
            {
                var map = JsonReader.ParseObject(response.Body);
                string? message = JsonReader.StringOrNull(map, "message") ?? JsonReader.StringOrNull(map, "error");
                if (!string.IsNullOrWhiteSpace(message)) return message!;
            }
            catch (JsonReader.JsonException)
            {
                // не разобрали — ниже общий текст
            }
            return "панель отклонила обращение";
        }
    }
}
