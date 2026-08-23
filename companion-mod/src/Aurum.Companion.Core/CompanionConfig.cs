using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;

namespace Aurum.Companion.Core
{
    /// <summary>
    /// Настройки мода. Формат — «ключ = значение», по строке на настройку.
    /// </summary>
    /// <remarks>
    /// Не XML и не JSON намеренно. Этот файл правит человек на живом сервере,
    /// иногда через ssh и в спешке; забытая запятая в JSON или незакрытый тег
    /// в XML стоили бы ему незагрузившегося мода. Пропущенная строка здесь
    /// стоит значения по умолчанию.
    ///
    /// В файле лежит токен — то есть это секрет. Мод не печатает его ни при
    /// загрузке, ни в ошибках; при записи файла по умолчанию права ужимаются
    /// до владельца.
    /// </remarks>
    public sealed class CompanionConfig
    {
        /// <summary>Адрес панели внутри приватного туннеля: http://10.0.0.1:3001</summary>
        public string PanelBaseUrl { get; private set; } = "";

        /// <summary>Идентификатор сервера в панели — он же в её адресной строке.</summary>
        public string PanelServerId { get; private set; } = "";

        /// <summary>Общий секрет. Им мод авторизуется в панели, а панель — в моде.</summary>
        public string Token { get; private set; } = "";

        /// <summary>Адрес, на котором мод слушает панель. По умолчанию — только петля.</summary>
        public string ListenHost { get; private set; } = "127.0.0.1";

        public int ListenPort { get; private set; } = 8110;

        /// <summary>Сколько секунд игрок ждёт между своими обращениями.</summary>
        public int TicketCooldownSeconds { get; private set; } = 60;

        /// <summary>Слать ли в панель сообщения игрового чата.</summary>
        public bool ForwardChat { get; private set; } = true;

        /// <summary>Слать ли смерти и убийства игроков игроками.</summary>
        public bool ForwardDeaths { get; private set; } = true;

        /// <summary>Максимум событий в очереди, пока панель недоступна.</summary>
        public int EventQueueLimit { get; private set; } = 500;

        public static CompanionConfig Parse(IEnumerable<string> lines)
        {
            var config = new CompanionConfig();
            var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

            foreach (string raw in lines)
            {
                string line = raw.Trim();
                if (line.Length == 0 || line[0] == '#' || line[0] == ';') continue;

                int eq = line.IndexOf('=');
                if (eq <= 0) continue;
                values[line.Substring(0, eq).Trim()] = line.Substring(eq + 1).Trim();
            }

            config.PanelBaseUrl = Get(values, "panel-url", "").TrimEnd('/');
            config.PanelServerId = Get(values, "server-id", "");
            config.Token = Get(values, "token", "");
            config.ListenHost = Get(values, "listen-host", "127.0.0.1");
            config.ListenPort = GetInt(values, "listen-port", 8110, 1, 65535);
            config.TicketCooldownSeconds = GetInt(values, "ticket-cooldown-seconds", 60, 0, 86400);
            config.ForwardChat = GetBool(values, "forward-chat", true);
            config.ForwardDeaths = GetBool(values, "forward-deaths", true);
            config.EventQueueLimit = GetInt(values, "event-queue-limit", 500, 10, 100000);
            return config;
        }

        public static CompanionConfig Load(string path) => Parse(File.ReadAllLines(path));

        /// <summary>
        /// Чего не хватает для работы. Пустой список — всё на месте.
        /// </summary>
        /// <remarks>
        /// Проверка отдельно от разбора, чтобы мод мог сказать в журнал
        /// «не настроен, работаю вхолостую» и не падать: упавший мод в
        /// 7 Days to Die утаскивает за собой запуск сервера.
        /// </remarks>
        public IReadOnlyList<string> Problems()
        {
            var problems = new List<string>();
            if (PanelBaseUrl.Length == 0) problems.Add("не задан panel-url");
            else if (!PanelBaseUrl.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
                     && !PanelBaseUrl.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            {
                problems.Add("panel-url должен начинаться с http:// или https://");
            }
            if (PanelServerId.Length == 0) problems.Add("не задан server-id");
            // Длина, а не сам токен: в журнал секрет не попадает даже частично.
            if (Token.Length < 16) problems.Add("token короче 16 символов");
            return problems;
        }

        private static string Get(Dictionary<string, string> values, string key, string fallback) =>
            values.TryGetValue(key, out string? value) && value.Length > 0 ? value : fallback;

        private static int GetInt(Dictionary<string, string> values, string key, int fallback, int min, int max)
        {
            if (!values.TryGetValue(key, out string? text)) return fallback;
            if (!int.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out int value)) return fallback;
            // Значение вне разумных границ — это опечатка, а не намерение.
            return value < min || value > max ? fallback : value;
        }

        private static bool GetBool(Dictionary<string, string> values, string key, bool fallback)
        {
            if (!values.TryGetValue(key, out string? text)) return fallback;
            switch (text.Trim().ToLowerInvariant())
            {
                case "true": case "yes": case "1": case "on": return true;
                case "false": case "no": case "0": case "off": return false;
                default: return fallback;
            }
        }
    }
}
