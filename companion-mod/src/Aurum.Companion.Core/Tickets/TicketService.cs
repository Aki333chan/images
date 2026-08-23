using System;
using System.Threading;
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
        public void Run(Action work) => ThreadPool.QueueUserWorkItem(_ => work());
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

        public TicketService(
            PanelClient panel,
            IGameBridge game,
            TicketCooldown cooldown,
            IWorkDispatcher? dispatcher = null,
            Func<DateTimeOffset>? clock = null)
        {
            _panel = panel;
            _game = game;
            _cooldown = cooldown;
            _dispatcher = dispatcher ?? new ThreadPoolDispatcher();
            _clock = clock ?? (() => DateTimeOffset.UtcNow);
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

            var now = _clock();
            int wait = _cooldown.RemainingSeconds(player.PlayerId, now);
            if (wait > 0)
            {
                Tell(player, "Слишком часто. Следующее обращение через " + wait + " с.");
                return true;
            }

            // Дальше — сеть. Из главного потока нельзя.
            var snapshot = player;
            _dispatcher.Run(() => SendAndReply(snapshot, command));
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
