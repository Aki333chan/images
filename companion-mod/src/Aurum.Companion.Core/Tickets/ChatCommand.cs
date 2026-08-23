using System;

namespace Aurum.Companion.Core.Tickets
{
    public enum ChatCommandKind
    {
        /// <summary>Обычное сообщение — мод в него не вмешивается.</summary>
        None,
        Ticket,
        Report,
        Help,
    }

    /// <summary>Разобранная команда игрока из чата.</summary>
    public sealed class ChatCommand
    {
        private ChatCommand(ChatCommandKind kind)
        {
            Kind = kind;
        }

        public ChatCommandKind Kind { get; }

        /// <summary>Текст обращения — для Ticket, либо причина — для Report.</summary>
        public string Text { get; private set; } = "";

        /// <summary>На кого жалуются — только для Report.</summary>
        public string Accused { get; private set; } = "";

        /// <summary>Почему команда не принята. Пусто — принята.</summary>
        public string Problem { get; private set; } = "";

        public bool IsValid => Kind != ChatCommandKind.None && Problem.Length == 0;

        /// <summary>Максимум, который примет панель, — длиннее обрежется на её стороне.</summary>
        public const int MaxTextLength = 500;

        /// <summary>Слишком короткое обращение бесполезно и разбирающему, и игроку.</summary>
        public const int MinTextLength = 3;

        /// <summary>
        /// Разбирает сообщение игрового чата.
        /// </summary>
        /// <remarks>
        /// Команды начинаются с косой черты — так их пишут игроки во всех играх,
        /// и такое сообщение мод проглатывает, а не пересылает в общий чат:
        /// жалоба не должна быть видна тому, на кого жалуются.
        ///
        /// Разбор нарочно терпимый к регистру и лишним пробелам: человек пишет
        /// это в панике, стоя перед зомби.
        /// </remarks>
        public static ChatCommand Parse(string? message)
        {
            string text = (message ?? "").Trim();
            if (text.Length < 2 || text[0] != '/') return new ChatCommand(ChatCommandKind.None);

            int space = text.IndexOf(' ');
            string name = (space < 0 ? text.Substring(1) : text.Substring(1, space - 1)).ToLowerInvariant();
            string rest = space < 0 ? "" : text.Substring(space + 1).Trim();

            switch (name)
            {
                case "ticket":
                case "тикет":
                case "help":
                case "помощь":
                    // /help без текста — это просьба объяснить, а не пустой тикет.
                    if (rest.Length == 0 && (name == "help" || name == "помощь"))
                    {
                        return new ChatCommand(ChatCommandKind.Help);
                    }
                    return Ticket(rest);

                case "report":
                case "жалоба":
                    return Report(rest);

                default:
                    return new ChatCommand(ChatCommandKind.None);
            }
        }

        private static ChatCommand Ticket(string rest)
        {
            var command = new ChatCommand(ChatCommandKind.Ticket) { Text = Squash(rest) };
            if (command.Text.Length < MinTextLength)
            {
                command.Problem = "Напишите, что случилось: /ticket пропали вещи после смерти";
            }
            else if (command.Text.Length > MaxTextLength)
            {
                command.Problem = "Слишком длинно — уложитесь в " + MaxTextLength + " символов";
            }
            return command;
        }

        private static ChatCommand Report(string rest)
        {
            var command = new ChatCommand(ChatCommandKind.Report);
            int space = rest.IndexOf(' ');
            if (space < 0)
            {
                command.Problem = "Нужен ник и причина: /report Ник ломает чужую базу";
                return command;
            }

            command.Accused = rest.Substring(0, space).Trim();
            command.Text = Squash(rest.Substring(space + 1));

            if (command.Accused.Length == 0)
            {
                command.Problem = "Не указан ник";
            }
            else if (command.Text.Length < MinTextLength)
            {
                command.Problem = "Напишите причину: /report Ник ломает чужую базу";
            }
            else if (command.Text.Length > MaxTextLength)
            {
                command.Problem = "Слишком длинно — уложитесь в " + MaxTextLength + " символов";
            }
            return command;
        }

        /// <summary>
        /// Схлопывает пробелы и убирает управляющие символы.
        /// </summary>
        /// <remarks>
        /// Управляющие символы важнее, чем кажется: текст отсюда попадает и в
        /// журнал сервера, и в панель, а перевод строки внутри сообщения умеет
        /// подделывать чужую строку журнала.
        /// </remarks>
        private static string Squash(string value)
        {
            var sb = new System.Text.StringBuilder(value.Length);
            bool lastWasSpace = false;
            foreach (char c in value)
            {
                char ch = char.IsControl(c) ? ' ' : c;
                if (ch == ' ')
                {
                    if (sb.Length == 0 || lastWasSpace) continue;
                    lastWasSpace = true;
                    sb.Append(' ');
                    continue;
                }
                lastWasSpace = false;
                sb.Append(ch);
            }
            return sb.ToString().Trim();
        }
    }
}
