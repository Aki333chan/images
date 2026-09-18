using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading.Tasks;

namespace Aurum.Companion.Core.Game
{
    /// <summary>Bounded game-thread dispatch; timed-out queued work never runs later.</summary>
    public sealed class GameThreadDispatcher : IDisposable
    {
        private sealed class Work
        {
            internal readonly Func<object?> Run;
            internal readonly Stopwatch Age = Stopwatch.StartNew();
            internal readonly TaskCompletionSource<object?> Result =
                new TaskCompletionSource<object?>(TaskCreationOptions.RunContinuationsAsynchronously);
            internal int State; // 0 queued, 1 executing, 2 finished, 3 cancelled
            internal Work(Func<object?> run) { Run = run; }
        }

        private readonly object _gate = new object();
        private readonly HashSet<Work> _pending = new HashSet<Work>();
        private readonly Action<Action> _post;
        private readonly Func<bool> _isMainThread;
        private readonly int _capacity;
        private readonly TimeSpan _timeout;
        private bool _stopped;

        public GameThreadDispatcher(Action<Action> post, Func<bool> isMainThread,
            int capacity = 32, TimeSpan? timeout = null)
        {
            _post = post ?? throw new ArgumentNullException(nameof(post));
            _isMainThread = isMainThread ?? throw new ArgumentNullException(nameof(isMainThread));
            if (capacity < 1) throw new ArgumentOutOfRangeException(nameof(capacity));
            _timeout = timeout ?? TimeSpan.FromSeconds(2);
            if (_timeout <= TimeSpan.Zero || _timeout.TotalMilliseconds > int.MaxValue)
                throw new ArgumentOutOfRangeException(nameof(timeout));
            _capacity = capacity;
        }

        public T Invoke<T>(Func<T> action)
        {
            if (action == null) throw new ArgumentNullException(nameof(action));
            if (_isMainThread())
            {
                lock (_gate)
                    if (_stopped) throw new GameDispatchException("Game dispatcher is stopped", false);
                // Never hold the bookkeeping lock while game code runs: other
                // callers must be able to time out or shut down independently.
                return action();
            }
            Work item;
            lock (_gate)
            {
                if (_stopped) throw new GameDispatchException("Game dispatcher is stopped", false);
                if (_pending.Count >= _capacity)
                    throw new GameDispatchException("Game dispatcher is busy", false);
                item = new Work(() => action());
                _pending.Add(item);
            }
            try { _post(() => Execute(item)); }
            catch
            {
                lock (_gate) { _pending.Remove(item); item.State = 3; }
                throw new GameDispatchException("Cannot queue game work", false);
            }

            if (Task.WaitAny(new Task[] { item.Result.Task }, _timeout) < 0)
            {
                lock (_gate)
                {
                    if (item.State < 2) Cancel(item, true);
                    // Retain the slot until the game consumes the callback, otherwise
                    // repeated timeouts can fill a frozen game queue without bound.
                }
            }
            return (T)item.Result.Task.GetAwaiter().GetResult()!;
        }

        private void Cancel(Work item, bool timedOut)
        {
            bool started = item.State == 1;
            item.State = 3;
            item.Result.TrySetException(new GameDispatchException(
                timedOut ? "Game dispatch deadline exceeded" : "Game dispatcher stopped", started, timedOut));
        }

        private void Execute(Work item)
        {
            lock (_gate)
            {
                if (item.State == 0 && item.Age.Elapsed >= _timeout) Cancel(item, true);
                if (item.State != 0) { _pending.Remove(item); return; }
                item.State = 1;
            }
            object? result = null;
            Exception? error = null;
            try { result = item.Run(); }
            catch (Exception e) { error = e; }
            lock (_gate)
            {
                _pending.Remove(item);
                if (item.State != 1) return;
                item.State = 2;
                if (error != null) item.Result.TrySetException(error);
                else item.Result.TrySetResult(result);
            }
        }

        public void Dispose()
        {
            lock (_gate)
            {
                _stopped = true;
                foreach (Work item in _pending)
                    if (item.State < 2) Cancel(item, false);
                _pending.Clear();
            }
        }
    }

    public sealed class GameDispatchException : Exception
    {
        public bool MayHaveExecuted { get; }
        public bool TimedOut { get; }
        public GameDispatchException(string message, bool mayHaveExecuted, bool timedOut = false) : base(message)
        {
            MayHaveExecuted = mayHaveExecuted;
            TimedOut = timedOut;
        }
    }
}
