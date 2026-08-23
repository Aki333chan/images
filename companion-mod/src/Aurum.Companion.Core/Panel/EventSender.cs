using System;
using System.Collections.Generic;
using System.Threading;
using Aurum.Companion.Core.Game;

namespace Aurum.Companion.Core.Panel
{
    /// <summary>
    /// Отдельный поток, который вывозит события в панель.
    /// </summary>
    /// <remarks>
    /// ПОЧЕМУ ОТДЕЛЬНЫЙ ПОТОК. Обработчики событий игры вызываются в главном
    /// потоке сервера — том самом, который считает мир. Сетевой вызов оттуда
    /// подвешивает всех игроков ровно на столько, на сколько задумалась сеть.
    /// Поэтому обработчик только кладёт событие в очередь и немедленно
    /// возвращается, а ходит по сети этот поток.
    ///
    /// Панель может лежать. Тогда поток отступает по нарастающей и продолжает
    /// копить события в очереди, а очередь ограничена и выбрасывает старое.
    /// Мод при этом не мешает игре: сервер живёт, просто панель какое-то время
    /// не видит событий.
    /// </remarks>
    public sealed class EventSender
    {
        /// <summary>Сколько событий уходит одним запросом.</summary>
        public const int BatchSize = 50;

        private const int IdlePollMs = 1000;

        private readonly EventQueue _queue;
        private readonly PanelClient _panel;
        private readonly IGameBridge _game;
        private readonly ManualResetEvent _stop = new ManualResetEvent(false);

        private Thread? _thread;
        private int _failures;

        public EventSender(EventQueue queue, PanelClient panel, IGameBridge game)
        {
            _queue = queue;
            _panel = panel;
            _game = game;
        }

        /// <summary>
        /// Пауза перед следующей попыткой после подряд идущих неудач.
        /// </summary>
        /// <remarks>
        /// Удвоение с потолком в минуту. Потолок нужен, чтобы после долгого
        /// падения панели мод заметил её возвращение за минуту, а не через час,
        /// как вышло бы при неограниченном удвоении.
        /// </remarks>
        public static int BackoffMs(int consecutiveFailures)
        {
            if (consecutiveFailures <= 0) return 0;
            const int baseMs = 2000;
            const int maxMs = 60000;

            long delay = baseMs;
            for (int i = 1; i < consecutiveFailures && delay < maxMs; i++) delay *= 2;
            return (int)Math.Min(delay, maxMs);
        }

        public void Start()
        {
            if (_thread != null) return;
            _thread = new Thread(Loop)
            {
                // Фоновый: не должен мешать серверу завершиться.
                IsBackground = true,
                Name = "aurum-companion-events",
            };
            _thread.Start();
        }

        public void Stop()
        {
            _stop.Set();
            // Ждём ограниченно: при остановке сервера у мода нет права
            // задерживать выключение из-за неотвеченной панели.
            _thread?.Join(TimeSpan.FromSeconds(3));
            _thread = null;
        }

        private void Loop()
        {
            while (!_stop.WaitOne(0))
            {
                List<GameEvent> batch = _queue.Take(BatchSize);
                if (batch.Count == 0)
                {
                    if (_stop.WaitOne(IdlePollMs)) return;
                    continue;
                }

                PanelResponse response;
                try
                {
                    response = _panel.SendEvents(batch);
                }
                catch (Exception e)
                {
                    // Транспорт ошибки не бросает, но чужой код мог измениться —
                    // упавший поток отправки означал бы, что события тихо
                    // перестали доходить, и никто об этом не узнает.
                    _game.LogError("Отправка событий сорвалась", e);
                    response = new PanelResponse(0, "");
                }

                if (response.IsSuccess)
                {
                    _failures = 0;
                    continue;
                }

                if (response.IsRetryable)
                {
                    _queue.Requeue(batch);
                    _failures++;
                    if (_failures == 1 || _failures % 10 == 0)
                    {
                        // Не на каждую неудачу: при долгом падении панели журнал
                        // сервера иначе заполнится одной и той же строкой.
                        _game.Log("Панель не принимает события (" + response.Status + "), повторю позже");
                    }
                    if (_stop.WaitOne(BackoffMs(_failures))) return;
                    continue;
                }

                // Панель поняла запрос и отказала. Повтор ничего не изменит,
                // а очередь встанет намертво — выбрасываем пачку.
                _game.Log("Панель отклонила пачку событий (" + response.Status + "), пачка отброшена");
                _failures = 0;
            }
        }
    }
}
