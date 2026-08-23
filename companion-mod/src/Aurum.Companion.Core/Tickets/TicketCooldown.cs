using System;
using System.Collections.Generic;

namespace Aurum.Companion.Core.Tickets
{
    /// <summary>
    /// Антифлуд: сколько игроку ждать до следующего обращения.
    /// </summary>
    /// <remarks>
    /// Считается на стороне мода, а не панели, и это осознанно: смысл в том,
    /// чтобы поток сообщений вообще не доходил до сети. Проверка в панели
    /// защитила бы её базу, но не канал и не главный поток игры.
    ///
    /// Время берётся снаружи, а не из DateTime.UtcNow внутри — иначе тест на
    /// «прошла минута» пришлось бы писать через настоящий сон на минуту.
    /// </remarks>
    public sealed class TicketCooldown
    {
        private readonly object _lock = new object();
        private readonly Dictionary<string, DateTimeOffset> _lastUse = new Dictionary<string, DateTimeOffset>(StringComparer.Ordinal);
        private readonly TimeSpan _interval;

        public TicketCooldown(int seconds)
        {
            _interval = TimeSpan.FromSeconds(seconds < 0 ? 0 : seconds);
        }

        /// <summary>Сколько секунд осталось ждать. 0 — можно.</summary>
        public int RemainingSeconds(string playerId, DateTimeOffset now)
        {
            if (_interval <= TimeSpan.Zero) return 0;
            lock (_lock)
            {
                if (!_lastUse.TryGetValue(playerId, out var last)) return 0;
                var passed = now - last;
                if (passed >= _interval) return 0;
                // Вверх: сказать «осталось 0 секунд» и всё равно отказать — хуже,
                // чем сказать «1».
                return (int)Math.Ceiling((_interval - passed).TotalSeconds);
            }
        }

        /// <summary>Отметить успешное обращение. Неудачные не отмечаются — иначе упавшая панель молчала бы минуту.</summary>
        public void Mark(string playerId, DateTimeOffset now)
        {
            lock (_lock)
            {
                _lastUse[playerId] = now;
            }
        }

        /// <summary>Забыть игрока — при выходе с сервера.</summary>
        public void Forget(string playerId)
        {
            lock (_lock)
            {
                _lastUse.Remove(playerId);
            }
        }
    }
}
