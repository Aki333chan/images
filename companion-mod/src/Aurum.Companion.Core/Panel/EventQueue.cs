using System.Collections.Generic;
using Aurum.Companion.Core.Game;

namespace Aurum.Companion.Core.Panel
{
    /// <summary>
    /// Очередь событий, ждущих отправки в панель.
    /// </summary>
    /// <remarks>
    /// Очередь ограничена, и при переполнении выбрасывается САМОЕ СТАРОЕ
    /// событие, а не новое. Логика такая: очередь переполняется, только когда
    /// панель лежит; когда она поднимется, свежая картина полезнее вчерашней.
    /// Расти без предела нельзя — мод живёт в процессе игрового сервера, и
    /// съеденная им память отнимается у мира.
    ///
    /// Класс потокобезопасен: складывают события обработчики игры из главного
    /// потока, забирает — поток отправки.
    /// </remarks>
    public sealed class EventQueue
    {
        private readonly object _lock = new object();
        private readonly Queue<GameEvent> _items = new Queue<GameEvent>();
        private readonly int _limit;

        /// <summary>Сколько событий пришлось выбросить за всё время.</summary>
        public long Dropped { get; private set; }

        public EventQueue(int limit)
        {
            _limit = limit < 1 ? 1 : limit;
        }

        public int Count
        {
            get { lock (_lock) return _items.Count; }
        }

        public void Enqueue(GameEvent item)
        {
            lock (_lock)
            {
                while (_items.Count >= _limit)
                {
                    _items.Dequeue();
                    Dropped++;
                }
                _items.Enqueue(item);
            }
        }

        /// <summary>
        /// Забрать до <paramref name="max"/> событий.
        /// </summary>
        /// <remarks>
        /// События именно ЗАБИРАЮТСЯ, а не подсматриваются: если отправка не
        /// удалась, вызывающий возвращает их назад через <see cref="Requeue"/>.
        /// Иначе пришлось бы держать «отправляется сейчас» отдельным
        /// состоянием и однажды его рассинхронизировать.
        /// </remarks>
        public List<GameEvent> Take(int max)
        {
            var batch = new List<GameEvent>();
            lock (_lock)
            {
                while (batch.Count < max && _items.Count > 0) batch.Add(_items.Dequeue());
            }
            return batch;
        }

        /// <summary>Вернуть неотправленное в начало очереди, сохранив порядок.</summary>
        public void Requeue(IReadOnlyList<GameEvent> batch)
        {
            if (batch.Count == 0) return;
            lock (_lock)
            {
                var rest = _items.ToArray();
                _items.Clear();
                foreach (var item in batch) _items.Enqueue(item);
                foreach (var item in rest) _items.Enqueue(item);

                // После возврата очередь может оказаться длиннее предела —
                // подрезаем по тому же правилу, что и при обычной вставке.
                while (_items.Count > _limit)
                {
                    _items.Dequeue();
                    Dropped++;
                }
            }
        }
    }
}
