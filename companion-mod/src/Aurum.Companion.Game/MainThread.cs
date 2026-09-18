using System;
using System.Threading;
using Aurum.Companion.Core.Game;

namespace Aurum.Companion.Game
{
    /// <summary>
    /// Выполнение работы в главном потоке сервера.
    /// </summary>
    /// <remarks>
    /// ЗАЧЕМ ЭТО ВООБЩЕ. Мир 7 Days to Die считает один поток — тот же, что и
    /// у Unity. Обратиться к миру из другого потока значит уронить сервер, и
    /// не сразу, а когда повезёт: такие падения потом ищут неделями.
    ///
    /// А ядро мода живёт как раз в чужих потоках: HTTP-сервер отвечает панели
    /// из потока прослушивания, события уходят из потока отправки. Значит
    /// каждое касание мира обязано пройти здесь.
    ///
    /// Используем штатную очередь игры, без зависимости от Unity SynchronizationContext.
    /// </remarks>
    internal static class MainThread
    {
        private static GameThreadDispatcher? _dispatcher;

        public static void Capture()
        {
            _dispatcher = new GameThreadDispatcher(
                action => ThreadManager.AddSingleTaskMainThread("AurumCompanion", action),
                ThreadManager.IsMainThread);
        }

        /// <summary>Есть ли куда откладывать. false — игра ещё не запустилась.</summary>
        public static bool Ready => _dispatcher != null;

        /// <summary>
        /// Выполнить и дождаться результата.
        /// </summary>
        /// <remarks>
        /// Не более 32 незавершённых callbacks и 2 секунд ожидания.
        /// Просроченная работа не запускается; результат уже начатой может быть неизвестен.
        /// </remarks>
        public static T Get<T>(Func<T> work)
        {
            var dispatcher = _dispatcher;
            if (dispatcher == null) throw new GameDispatchException("Game bridge is unavailable", false);
            return dispatcher.Invoke(work);
        }

        public static void Stop()
        {
            Interlocked.Exchange(ref _dispatcher, null)?.Dispose();
        }
    }
}
