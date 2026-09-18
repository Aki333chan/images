using System;
using System.Collections.Generic;
using System.Globalization;

namespace Aurum.Companion.Core
{
    public enum MessageKey
    {
        None, Sender, Help, TicketUsage, ReportUsage, MissingName, TooLong,
        Pending, Cooldown, Busy, Failed, Rejected, ReportSent, TicketSent, TicketAppended,
        Unavailable, Unauthorized, RateLimited, PanelError, PanelRejected
    }

    /// <summary>Immutable, shared catalog: no disk access or per-player locale cache.</summary>
    public sealed class Messages
    {
        private static readonly IReadOnlyDictionary<MessageKey, string[]> Catalog =
            new Dictionary<MessageKey, string[]>
            {
                [MessageKey.None] = new[] { "", "", "" },
                [MessageKey.Sender] = new[] { "Panel", "Панель", "Panel" },
                [MessageKey.Help] = new[] {
                    "Commands: /ticket <what happened> — contact staff; /report <name> <reason> — report a player.",
                    "Команды: /ticket <что случилось> — написать администрации, /report <ник> <причина> — пожаловаться на игрока.",
                    "Komendy: /ticket <co się stało> — kontakt z administracją; /report <nick> <powód> — zgłoś gracza." },
                [MessageKey.TicketUsage] = new[] { "Describe the problem: /ticket items lost after death", "Напишите, что случилось: /ticket пропали вещи после смерти", "Opisz problem: /ticket przedmioty zniknęły po śmierci" },
                [MessageKey.ReportUsage] = new[] { "Provide a name and reason: /report Name destroying someone else's base", "Нужен ник и причина: /report Ник ломает чужую базу", "Podaj nick i powód: /report Nick niszczy cudzą bazę" },
                [MessageKey.MissingName] = new[] { "Player name is missing.", "Не указан ник", "Nie podano nicku." },
                [MessageKey.TooLong] = new[] { "Too long — limit: {0} characters.", "Слишком длинно — уложитесь в {0} символов", "Za długie — limit: {0} znaków." },
                [MessageKey.Pending] = new[] { "Your previous request is still being sent. Please wait.", "Предыдущее обращение ещё отправляется. Дождитесь ответа.", "Poprzednie zgłoszenie jest nadal wysyłane. Poczekaj." },
                [MessageKey.Cooldown] = new[] { "Please wait {0} s before your next request.", "Слишком часто. Следующее обращение через {0} с.", "Poczekaj {0} s przed kolejnym zgłoszeniem." },
                [MessageKey.Busy] = new[] { "Requests are busy. Try again later.", "Отправка обращений занята. Попробуйте позже.", "Wysyłanie zgłoszeń jest zajęte. Spróbuj później." },
                [MessageKey.Failed] = new[] { "Could not send your request. Try again later.", "Не удалось отправить обращение. Попробуйте позже.", "Nie udało się wysłać zgłoszenia. Spróbuj później." },
                [MessageKey.Rejected] = new[] { "Could not send: {0}. Please try again.", "Не удалось отправить: {0}. Попробуйте ещё раз.", "Nie udało się wysłać: {0}. Spróbuj ponownie." },
                [MessageKey.ReportSent] = new[] { "Report sent to staff.", "Жалоба отправлена администрации.", "Zgłoszenie wysłane do administracji." },
                [MessageKey.TicketSent] = new[] { "Ticket sent. The reply will arrive here in chat.", "Обращение отправлено. Ответ придёт сюда же, в чат.", "Zgłoszenie wysłane. Odpowiedź otrzymasz tutaj, na czacie." },
                [MessageKey.TicketAppended] = new[] { "Added to your open ticket.", "Дописано к вашему открытому обращению.", "Dodano do Twojego otwartego zgłoszenia." },
                [MessageKey.Unavailable] = new[] { "panel unavailable", "панель недоступна", "panel niedostępny" },
                [MessageKey.Unauthorized] = new[] { "server not authorized in the panel", "сервер не авторизован в панели", "serwer nie jest autoryzowany w panelu" },
                [MessageKey.RateLimited] = new[] { "too many requests, try later", "слишком часто, попробуйте позже", "zbyt wiele zgłoszeń, spróbuj później" },
                [MessageKey.PanelError] = new[] { "panel error", "панель отвечает ошибкой", "błąd panelu" },
                [MessageKey.PanelRejected] = new[] { "panel rejected the request", "панель отклонила обращение", "panel odrzucił zgłoszenie" },
            };

        private readonly int _index;
        public string Language { get; }
        public Messages(string? language)
        {
            Language = Normalize(language);
            _index = Language == "ru" ? 1 : Language == "pl" ? 2 : 0;
        }
        public static string Normalize(string? language)
        {
            string value = (language ?? "").Trim().ToLowerInvariant();
            return value == "ru" || value == "pl" ? value : "en";
        }
        public string Get(MessageKey key, params object[] args) =>
            string.Format(CultureInfo.InvariantCulture, Catalog[key][_index], args);
    }
}
