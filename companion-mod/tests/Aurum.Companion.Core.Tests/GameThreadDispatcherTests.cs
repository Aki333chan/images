using System;
using System.Collections.Concurrent;
using System.Threading;
using System.Threading.Tasks;
using Aurum.Companion.Core.Game;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public class GameThreadDispatcherTests
{
    private sealed class Scheduler
    {
        private readonly BlockingCollection<Action> _queue = new();
        public void Post(Action work) => _queue.Add(work);
        public Action Take()
        {
            Assert.True(_queue.TryTake(out var callback, TimeSpan.FromSeconds(5)));
            return callback!;
        }
    }

    [Fact]
    public void Main_thread_runs_inline_without_posting()
    {
        using var dispatcher = new GameThreadDispatcher(_ => throw new Exception("Should not post"), () => true);
        Assert.Equal(42, dispatcher.Invoke(() => 42));
        Assert.Equal(43, dispatcher.Invoke(() => dispatcher.Invoke(() => 43)));
    }

    [Fact]
    public async Task Worker_gets_result_after_game_callback()
    {
        var scheduler = new Scheduler();
        using var dispatcher = new GameThreadDispatcher(scheduler.Post, () => false);
        var result = Task.Run(() => dispatcher.Invoke(() => 42));
        scheduler.Take()();
        Assert.Equal(42, await result);
    }

    [Fact]
    public async Task Work_exception_returns_to_caller_not_game_loop()
    {
        var scheduler = new Scheduler();
        using var dispatcher = new GameThreadDispatcher(scheduler.Post, () => false);
        var result = Task.Run(() => dispatcher.Invoke<int>(() => throw new InvalidOperationException("test")));
        scheduler.Take()();
        await Assert.ThrowsAsync<InvalidOperationException>(() => result);
    }

    [Fact]
    public async Task Expired_work_never_executes_and_retains_queue_capacity_until_consumed()
    {
        var scheduler = new Scheduler();
        using var dispatcher = new GameThreadDispatcher(scheduler.Post, () => false, 1, TimeSpan.FromMilliseconds(100));
        int calls = 0;
        var result = Task.Run(() => dispatcher.Invoke(() => ++calls));
        Action callback = scheduler.Take();
        var failure = await Assert.ThrowsAsync<GameDispatchException>(() => result);
        Assert.True(failure.TimedOut);
        Assert.False(failure.MayHaveExecuted);
        Assert.False(Assert.Throws<GameDispatchException>(() => dispatcher.Invoke(() => 1)).MayHaveExecuted);
        callback();
        Assert.Equal(0, calls);
        var next = Task.Run(() => dispatcher.Invoke(() => ++calls));
        scheduler.Take()();
        Assert.Equal(1, await next);
    }

    [Fact]
    public async Task Stop_wakes_waiter_and_prevents_late_execution()
    {
        var scheduler = new Scheduler();
        using var dispatcher = new GameThreadDispatcher(scheduler.Post, () => false);
        int calls = 0;
        var result = Task.Run(() => dispatcher.Invoke(() => ++calls));
        Action callback = scheduler.Take();
        dispatcher.Dispose();
        var failure = await Assert.ThrowsAsync<GameDispatchException>(() => result);
        Assert.False(failure.MayHaveExecuted);
        Assert.False(failure.TimedOut);
        callback();
        Assert.Equal(0, calls);
        Assert.Throws<GameDispatchException>(() => dispatcher.Invoke(() => 1));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Already_started_work_reports_unknown_outcome_on_timeout_or_stop(bool stop)
    {
        var scheduler = new Scheduler();
        using var dispatcher = new GameThreadDispatcher(scheduler.Post, () => false, 1, TimeSpan.FromSeconds(1));
        using var entered = new ManualResetEventSlim();
        using var release = new ManualResetEventSlim();
        var result = Task.Run(() => dispatcher.Invoke(() =>
        {
            entered.Set();
            if (!release.Wait(TimeSpan.FromSeconds(5))) throw new TimeoutException("Test stalled");
            return 1;
        }));
        var executing = Task.Run(scheduler.Take());
        try
        {
            Assert.True(entered.Wait(TimeSpan.FromSeconds(5)));
            if (stop) dispatcher.Dispose();
            var failure = await Assert.ThrowsAsync<GameDispatchException>(() => result);
            Assert.True(failure.MayHaveExecuted);
            Assert.Equal(!stop, failure.TimedOut);
        }
        finally { release.Set(); await executing; }
    }

    [Fact]
    public void Scheduling_failure_releases_capacity()
    {
        using var dispatcher = new GameThreadDispatcher(_ => throw new InvalidOperationException(), () => false, 1);
        for (int i = 0; i < 3; i++)
        {
            var failure = Assert.Throws<GameDispatchException>(() => dispatcher.Invoke(() => 1));
            Assert.False(failure.MayHaveExecuted);
            Assert.Contains("Cannot queue", failure.Message);
        }
    }

    [Fact]
    public async Task Inline_game_work_does_not_block_shutdown_bookkeeping()
    {
        using var entered = new ManualResetEventSlim();
        using var release = new ManualResetEventSlim();
        using var dispatcher = new GameThreadDispatcher(_ => throw new Exception(), () => true);
        var work = Task.Run(() => dispatcher.Invoke(() =>
        {
            entered.Set();
            if (!release.Wait(TimeSpan.FromSeconds(5))) throw new TimeoutException("Test stalled");
            return 7;
        }));
        try
        {
            Assert.True(entered.Wait(TimeSpan.FromSeconds(5)));
            await Task.Run(dispatcher.Dispose).WaitAsync(TimeSpan.FromSeconds(1));
        }
        finally { release.Set(); }
        Assert.Equal(7, await work); // Started game work is not forcibly interrupted.
    }
}
