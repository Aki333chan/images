using Aurum.Companion.Core.Game;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class ZoneSpawnChecksTests
{
    [Fact]
    public void HandoffPreservesSpawnCoordinatesUntilExplicitConsumption()
    {
        var queue = new ZoneSpawnChecks();
        Assert.True(queue.TryAdd(42, 7, -5.5, 8.5));
        Assert.Equal(1, queue.Count);
        Assert.True(queue.TryAdd(42, 7, 100, 100)); // Repeated notification keeps original position.
        Assert.Equal(1, queue.Count);
        Assert.True(queue.TryTake(out var check));
        Assert.Equal(42, check.EntityId);
        Assert.Equal(7, check.EntityClass);
        Assert.Equal(-5.5, check.X);
        Assert.Equal(8.5, check.Z);
        Assert.False(queue.TryTake(out _));
        Assert.True(queue.TryAdd(42, 7, 0, 0));
    }

    [Fact]
    public void BurstIsBoundedDeduplicatedAndDrainedWithAnUpdateBudget()
    {
        var queue = new ZoneSpawnChecks();
        for (int i = 0; i < ZoneSpawnChecks.Capacity; i++) Assert.True(queue.TryAdd(i, 1, i, 0));
        Assert.True(queue.TryAdd(0, 1, 0, 0));
        Assert.False(queue.TryAdd(ZoneSpawnChecks.Capacity, 1, 0, 0));
        int budget = System.Math.Min(ZoneSpawnChecks.PerUpdate, queue.Count);
        for (int i = 0; i < budget; i++)
        {
            Assert.True(queue.TryTake(out var check));
            Assert.Equal(i, check.EntityId);
        }
        Assert.Equal(ZoneSpawnChecks.Capacity - ZoneSpawnChecks.PerUpdate, queue.Count);
        Assert.True(queue.TryAdd(ZoneSpawnChecks.Capacity, 1, 0, 0));
        queue.Clear(); // World change, shutdown or runtime error.
        Assert.Equal(0, queue.Count);
        Assert.False(queue.TryTake(out _));
        Assert.True(queue.TryAdd(0, 1, 0, 0));
    }

    [Fact]
    public void DeferredCheckUsesCurrentRulesAndSpawnPositionNotGeneralDespawn()
    {
        var zone = new ZoneRule { X1 = -10, X2 = 10, Z1 = -10, Z2 = 10, BlockSpawn = 1 };
        var rules = new ZoneRules { Zones = new[] { zone } };
        var queue = new ZoneSpawnChecks();
        Assert.True(queue.TryAdd(1, 7, 0, 0));
        Assert.True(queue.TryTake(out var check));
        Assert.True(rules.DenyCreature(check.X, check.Z, 1, false));
        Assert.False(rules.DenyCreature(check.X, check.Z, 1, true));
        Assert.False(rules.DenyCreature(check.X, check.Z, 2, false));
        Assert.False(rules.DenyCreature(check.X, check.Z, 0, false));
        zone.Enabled = false;
        Assert.False(rules.DenyCreature(check.X, check.Z, 1, false));
        zone.Enabled = true; zone.ScheduleActive = false;
        Assert.False(rules.DenyCreature(check.X, check.Z, 1, false));
        zone.ScheduleActive = true; zone.BlockSpawn = 0;
        Assert.False(rules.DenyCreature(check.X, check.Z, 1, false));
    }
}
