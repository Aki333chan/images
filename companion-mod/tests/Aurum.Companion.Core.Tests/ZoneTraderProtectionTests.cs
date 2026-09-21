using System;
using Aurum.Companion.Core.Game;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class ZoneTraderProtectionTests
{
    private static ZoneRule Zone() => new ZoneRule { Id = "spawn", Name = "Spawn", X1 = -10, Z1 = -20, X2 = 10, Z2 = 20, TraderProtection = true };

    [Fact]
    public void SnapshotSurvivesMoveDisableAndDeleteUntilRestart()
    {
        var zone = Zone(); var rules = new ZoneRules { Zones = new[] { zone } };
        var applied = ZoneTraderProtection.Capture(rules);
        Assert.False(ZoneTraderProtection.Pending(applied, rules));
        zone.Name = "New name"; zone.NoPvp = false;
        Assert.False(ZoneTraderProtection.Pending(applied, rules));
        zone.X1 = -11;
        Assert.True(ZoneTraderProtection.Pending(applied, rules));
        Assert.Equal(-10, applied[0].X1);
        zone.X1 = -10;
        Assert.False(ZoneTraderProtection.Pending(applied, rules));
        zone.Enabled = false;
        Assert.True(ZoneTraderProtection.Pending(applied, rules));
        rules.Zones = Array.Empty<ZoneRule>();
        Assert.True(ZoneTraderProtection.Pending(applied, rules));
        Assert.False(ZoneTraderProtection.Pending(ZoneTraderProtection.Capture(rules), rules));
    }

    [Fact]
    public void ReorderingAndNonProtectZonesDoNotNeedRestart()
    {
        var a = Zone(); var b = Zone(); b.Id = "second";
        var rules = new ZoneRules { Zones = new[] { a, b } };
        var snapshot = ZoneTraderProtection.Capture(rules);
        Array.Reverse(rules.Zones);
        Assert.False(ZoneTraderProtection.Pending(snapshot, rules));
        b.TraderProtection = false;
        Assert.True(ZoneTraderProtection.Pending(snapshot, rules));
    }

    [Fact]
    public void ProtectRejectsScheduleFractionalOrOversizedGeometry()
    {
        var z = Zone();
        z.Schedule.Enabled = true;
        Assert.ThrowsAny<Exception>(() => ZoneTraderProtection.Validate(z));
        z.Schedule.Enabled = false; z.X1 = -10.5;
        Assert.ThrowsAny<Exception>(() => ZoneTraderProtection.Validate(z));
        z.X1 = -10; z.X2 = 32751;
        Assert.ThrowsAny<Exception>(() => ZoneTraderProtection.Validate(z));
        z.X2 = 32750;
        ZoneTraderProtection.Validate(z);
    }

    [Fact]
    public void LegacyImportDefaultsOffAndResponseStatusCannotPersist()
    {
        var z = Zone(); var rules = new ZoneRules { Zones = new[] { z } };
        var old = rules.Write().Replace(",\"traderProtection\":true", "");
        Assert.False(ZoneRules.Read(old, true).Zones[0].TraderProtection);
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(old));
        var response = rules.Write("{\"pending\":false,\"applied\":99,\"error\":\"\"}");
        Assert.Equal(rules.Write(), ZoneRules.Read(response).Write());
        Assert.True(ZoneRules.Read(rules.Write()).Zones[0].TraderProtection);
    }
}
