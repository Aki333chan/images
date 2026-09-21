using System;
using Aurum.Companion.Core.Game;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class ZoneBlockProtectionTests
{
    private static ZoneRule Zone() => new ZoneRule { Id = "spawn", Name = "Spawn", X1 = -10, Z1 = -20, X2 = 10, Z2 = 20 };

    [Fact]
    public void SourcesAreOptInIndependentAndNeverBlockDirectPlayerActions()
    {
        var z = Zone(); var rules = new ZoneRules { Zones = new[] { z } };
        Assert.False(rules.DenyBlockDamage(0, 0, true, false));
        Assert.False(rules.DenyBlockDamage(0, 0, false, true));
        z.NoCreatureBlockDamage = true;
        Assert.True(rules.DenyBlockDamage(-10, 20, false, true));
        Assert.False(rules.DenyBlockDamage(-10.01, 20, false, true));
        Assert.False(rules.DenyBlockDamage(0, 0, true, true)); // Explosion owns its source, even from a zombie.
        Assert.False(rules.DenyBlockDamage(0, 0, false, false));
        z.NoCreatureBlockDamage = false; z.NoExplosionBlockDamage = true;
        Assert.True(rules.DenyBlockDamage(10, -20, true, false));
        Assert.False(rules.DenyBlockDamage(10.01, -20, true, false));
        Assert.False(rules.DenyBlockDamage(0, 0, false, true));
        Assert.False(rules.DenyBlockDamage(0, 0, false, false));
        // Same zero-damage arithmetic as the audited native explosion's positive-hardness and fallback branches.
        Assert.Equal(0f, 500f / (2f * float.PositiveInfinity));
        Assert.Equal(0f, 500f / float.PositiveInfinity);
    }

    [Fact]
    public void OverlapsSchedulesAndDisabledZonesUseTargetBlockCoordinates()
    {
        var z = Zone(); z.NoExplosionBlockDamage = z.NoCreatureBlockDamage = true;
        var open = Zone(); open.Id = "open";
        var rules = new ZoneRules { Zones = new[] { open, z } };
        Assert.True(rules.DenyBlockDamage(0, 0, true, false));
        Array.Reverse(rules.Zones);
        Assert.True(rules.DenyBlockDamage(0, 0, true, false));
        z.Schedule = new ZoneSchedule { Enabled = true, End = 100 };
        rules.RefreshSchedules(99);
        Assert.True(rules.DenyBlockDamage(0, 0, false, true));
        rules.RefreshSchedules(100);
        Assert.False(rules.DenyBlockDamage(0, 0, true, false));
        z.Schedule.Enabled = false; z.Enabled = false; rules.RefreshSchedules(101);
        Assert.False(rules.DenyBlockDamage(0, 0, false, true));
    }

    [Fact]
    public void StrictWireSchemaAndSafeLegacyReadPreserveBothFlags()
    {
        var z = Zone(); var rules = new ZoneRules { Zones = new[] { z } };
        var json = rules.Write();
        const string fields = ",\"noCreatureBlockDamage\":false,\"noExplosionBlockDamage\":false";
        string legacy = json.Replace(fields, "");
        Assert.NotEqual(json, legacy);
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(legacy));
        Assert.False(ZoneRules.Read(legacy, true).Zones[0].NoCreatureBlockDamage);
        Assert.False(ZoneRules.Read(legacy, true).Zones[0].NoExplosionBlockDamage);
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(json.Replace("\"noCreatureBlockDamage\":false", "\"noCreatureBlockDamage\":\"false\"")));
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(json.Replace(",\"noExplosionBlockDamage\":false", ""), true));
        z.NoExplosionBlockDamage = z.NoCreatureBlockDamage = true;
        Assert.Equal(rules.Write(), ZoneRules.Read(rules.Write()).Write());
    }
}
