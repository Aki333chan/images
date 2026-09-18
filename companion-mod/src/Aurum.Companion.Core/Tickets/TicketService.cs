using System;
using System.Threading;
using System.Collections.Generic;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Panel;

namespace Aurum.Companion.Core.Tickets
{
    /// <summary>
    /// Куда отложить работу, которой нельзя занимать главный поток игры.
    /// </summary>
    /// <remarks>
    /// Интерфейс существует только ради тестов: в бою это пул потоков, в
    /// тестах — выполнение на месте. Иначе каждый тест на обращение к панели
    /// превращался бы в ожидание чужого потока со сном «на всякий случай».
    /// </remarks>
    public interface IWorkDispatcher
    {
        void Run(Action work);
    }

    /// <summary>Боевая реализация: обычный пул потоков.</summary>
    public sealed class ThreadPoolDispatcher : IWorkDispatcher
    {
        public void Run(Action work)
        {
            if (!ThreadPool.QueueUserWorkItem(_ => work())) throw new InvalidOperationException("Worker unavailable");
        }
    }

    /// <summary>
    /// Обращение игрока: приём, проверки, отправка в панель, ответ в чат.
    /// </summary>
    /// <remarks>
    /// ГЛАВНОЕ ПРО ПОТОКИ. Handle вызывается из обработчика чата, то есть из
    /// главного потока сервера. Сетевого вызова здесь нет и быть не может:
    /// пока мод ждёт панель, стоит весь мир. Поэтому проверки — на месте
    /// (они мгновенные), а поход в панель и ответ игроку — в отложенной работе.
    ///
    /// Игрок при этом не видит задержки как «зависания»: он отправил сообщение
    /// и через мгновение получил ответ, как в любом чате.
    /// </remarks>
    public sealed class TicketService
    {
        private readonly PanelClient _panel;
        private readonly IGameBridge _game;
        private readonly TicketCooldown _cooldown;
        private readonly IWorkDispatcher _dispatcher;
        private readonly Func<DateTimeOffset> _clock;
        private readonly object _gate = new object();
        private readonly HashSet<string> _pending = new HashSet<string>(StringComparer.Ordinal);
        private readonly int _maxPending;

        public TicketService(
            PanelClient panel,
            IGameBridge game,
            TicketCooldown cooldown,
            IWorkDispatcher? dispatcher = null,
            Func<DateTimeOffset>? clock = null,
            int maxPending = 4)
        {
            if (maxPending < 1 || maxPending > 16) throw new ArgumentOutOfRangeException(nameof(maxPending));
            _panel = panel;
            _game = game;
            _cooldown = cooldown;
            _dispatcher = dispatcher ?? new ThreadPoolDispatcher();
            _clock = clock ?? (() => DateTimeOffset.UtcNow);
            _maxPending = maxPending;
        }

        /// <summary>
        /// Обрабатывает команду игрока.
        /// </summary>
        /// <returns>
        /// true — сообщение забрал мод и в общий чат его пускать не нужно.
        /// Это важно для жалоб: /report не должен увидеть тот, на кого жалуются.
        /// </returns>
        public bool Handle(OnlinePlayer player, ChatCommand command)
        {
            switch (command.Kind)
            {
                case ChatCommandKind.None:
                    return false;

                case ChatCommandKind.Help:
                    Tell(player, "Команды: /ticket <что случилось> — написать администрации, /report <ник> <причина> — пожаловаться на игрока.");
                    return true;

                case ChatCommandKind.Ticket:
                case ChatCommandKind.Report:
                    break;

                default:
                    return false;
            }

            if (!command.IsValid)
            {
                Tell(player, command.Problem);
                return true;
            }

            string? rejection = null;
            lock (_gate)
            {
                int wait = _cooldown.RemainingSeconds(player.PlayerId, _clock());
                if (_pending.Contains(player.PlayerId)) rejection = "Предыдущее обращение ещё отправляется. Дождитесь ответа.";
                else if (wait > 0) rejection = "Слишком часто. Следующее обращение через " + wait + " с.";
                else if (_pending.Count >= _maxPending) rejection = "Отправка обращений занята. Попробуйте позже.";
                else _pending.Add(player.PlayerId);
            }
            if (rejection != null)
            {
                Tell(player, rejection);
                return true;
            }

            // Дальше — сеть. Из главного потока нельзя.
            var snapshot = player;
            try
            {
                _dispatcher.Run(() =>
                {
                    try { SendAndReply(snapshot, command); }
                    finally { lock (_gate) _pending.Remove(snapshot.PlayerId); }
                });
            }
            catch (Exception)
            {
                lock (_gate) _pending.Remove(snapshot.PlayerId);
                Tell(player, "Не удалось отправить обращение. Попробуйте позже.");
            }
            return true;
        }

        private void SendAndReply(OnlinePlayer player, ChatCommand command)
        {
            try
            {
                TicketResult result = command.Kind == ChatCommandKind.Report
                    ? _panel.SendReport(player.PlayerId, player.Name, command.Accused, command.Text, player)
                    : _panel.SendTicket(player.PlayerId, player.Name, command.Text);

                if (!result.Ok)
                {
                    // Отметку о времени НЕ ставим: обращение не дошло, и
                    // заставлять человека ждать минуту из-за нашей неудачи
                    // было бы несправедливо.
                    Tell(player, "Не удалось отправить: " + result.Error + ". Попробуйте ещё раз.");
                    return;
                }

                _cooldown.Mark(player.PlayerId, _clock());
                Tell(player, command.Kind == ChatCommandKind.Report
                    ? "Жалоба отправлена администрации."
                    : result.Created
                        ? "Обращение отправлено. Ответ придёт сюда же, в чат."
                        : "Дописано к вашему открытому обращению.");
            }
            catch (Exception e)
            {
                _game.LogError("Обращение игрока не отправлено", e);
                Tell(player, "Не удалось отправить обращение. Попробуйте позже.");
            }
        }

        private void Tell(OnlinePlayer player, string text)
        {
            try
            {
                _game.SendPrivateMessage(player.PlayerId, text);
            }
            catch (Exception e)
            {
                // Игрок мог выйти прямо сейчас — это не повод для шума.
                _game.LogError("Не удалось ответить игроку " + player.Name, e);
            }
        }
    }
}
