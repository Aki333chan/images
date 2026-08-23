using System;
using System.Threading;

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
    /// Контекст берётся в InitMod, где нас вызывает сама игра — то есть уже
    /// в главном потоке.
    /// </remarks>
    internal static class MainThread
    {
        private static SynchronizationContext? _context;

        public static void Capture()
        {
            _context = SynchronizationContext.Current;
        }

        /// <summary>Есть ли куда откладывать. false — игра ещё не запустилась.</summary>
        public static bool Ready => _context != null;

        /// <summary>
        /// Выполнить и дождаться результата.
        /// </summary>
        /// <remarks>
        /// Ждём намеренно: вызывающему нужен ответ — состояние мира или
        /// «дошло ли сообщение». Ценой того, что при зависшем сервере
        /// зависнет и поток панели; но зависший сервер — беда сама по себе,
        /// и маскировать её таймаутом здесь было бы хуже.
        /// </remarks>
        public static T Get<T>(Func<T> work, T fallback)
        {
            var context = _context;
            if (context == null) return fallback;

            T result = fallback;
            Exception? failure = null;
            context.Send(_ =>
            {
                try
                {
                    result = work();
                }
                catch (Exception e)
                {
                    // Исключение, брошенное здесь, ушло бы в главный поток игры
                    // и оборвало бы кадр. Переносим его к вызывающему.
                    failure = e;
                }
            }, null);

            if (failure != null) throw failure;
            return result;
        }

        /// <summary>Выполнить без ожидания — когда результат не нужен.</summary>
        public static void Post(Action work)
        {
            var context = _context;
            if (context == null) return;
            context.Post(_ =>
            {
                try
                {
                    work();
                }
                catch (Exception e)
                {
                    Log.Error("[AurumCompanion] Ошибка в отложенной работе: " + e);
                }
            }, null);
        }
    }
}
