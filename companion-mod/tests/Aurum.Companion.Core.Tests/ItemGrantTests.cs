using System;
using System.Threading.Tasks;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Json;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class ItemGrantTests
{
    private static ItemGrant Grant(ItemGrantGate gate) => ItemGrant.Read("Steam_123", "{\"sessionId\":\"" + gate.SessionId + "\",\"requestId\":\"" + Guid.NewGuid() + "\",\"itemId\":1,\"itemName\":\"test\",\"count\":5,\"quality\":0,\"confirmed\":true}");
    [Fact]
    public void Replay_and_parallel_calls_never_repeat_spawn()
    {
        var gate = new ItemGrantGate(); var request = Grant(gate); int calls = 0;
        Parallel.For(0, 20, _ => Assert.Equal("spawned", gate.Execute(request, () => { calls++; return "spawned"; })));
        Assert.Equal(1, calls);
        var other = Grant(gate); other.RequestId = request.RequestId; other.Count++;
        Assert.Equal("request_conflict", gate.Execute(other, () => throw new Exception()));
        other.Count = request.Count; other.PlayerId = "Steam_other";
        Assert.Equal("request_conflict", gate.Execute(other, () => throw new Exception()));
    }
    [Fact]
    public void Crash_and_restart_do_not_reexecute_unknown_attempt()
    {
        var gate = new ItemGrantGate(); var request = Grant(gate);
        Assert.Throws<InvalidOperationException>(() => gate.Execute(request, () => throw new InvalidOperationException()));
        Assert.Equal("unknown", gate.Execute(request, () => throw new Exception()));
        Assert.Equal("session_expired", new ItemGrantGate().Execute(request, () => throw new Exception()));
    }
    [Fact]
    public void Cancelled_dispatch_is_retained_as_not_started()
    {
        var gate = new ItemGrantGate(); var request = Grant(gate);
        Assert.Equal("not_ready", gate.Execute(request, () => throw new GameDispatchException("stopped", false)));
        Assert.Equal("not_ready", gate.Execute(request, () => throw new Exception()));
    }
    [Fact]
    public void Cooldown_and_ledger_capacity_fail_closed_without_eviction()
    {
        var time = DateTime.UtcNow; var gate = new ItemGrantGate(() => time);
        var first = Grant(gate);
        Assert.Equal("spawned", gate.Execute(first, () => "spawned"));
        Assert.Equal("cooldown", gate.Execute(Grant(gate), () => throw new Exception()));
        for (int i = 1; i < 4096; i++) { time = time.AddSeconds(2); Assert.Equal("spawned", gate.Execute(Grant(gate), () => "spawned")); }
        time = time.AddSeconds(2);
        Assert.Equal("capacity", gate.Execute(Grant(gate), () => throw new Exception()));
        Assert.Equal("spawned", gate.Execute(first, () => throw new Exception()));
    }
    [Theory]
    [InlineData("count", "-1")]
    [InlineData("count", "1001")]
    [InlineData("count", "1.5")]
    [InlineData("quality", "7")]
    [InlineData("itemId", "0")]
    [InlineData("confirmed", "false")]
    public void Strict_numeric_limits_and_confirmation(string field, string value)
    {
        var body = "{\"sessionId\":\"" + Guid.NewGuid() + "\",\"requestId\":\"" + Guid.NewGuid() + "\",\"itemId\":1,\"itemName\":\"test\",\"count\":5,\"quality\":0,\"confirmed\":true,\"" + field + "\":" + value + "}";
        Assert.Throws<JsonReader.JsonException>(() => ItemGrant.Read("Steam_123", body));
    }
}
