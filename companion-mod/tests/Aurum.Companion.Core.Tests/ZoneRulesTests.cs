using System;
using Aurum.Companion.Core.Game;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class ZoneRulesTests
{
    [Fact]
    public void CommandsRejectInjectionAndLegacyWritesButReadOldFilesExplicitly()
    {
        var rules = new ZoneRules { Zones = new[] { Safe() } };
        rules.Zones[0].EnterCommands = new[] { "buffplayer {player} buffExample", "teleportplayer {player} -12 -1 80" };
        Assert.Equal(rules.Write(), ZoneRules.Read(rules.Write()).Write());
        foreach (string command in new[] { "shutdown", "buffplayer Alice buffExample", "buffplayer {player} buffExample;shutdown", "buffplayer {player} aurumZoneProtection", "buffplayer {player} buffExample\nshutdown", "teleportplayer {player} 0 -2 0" })
            Assert.Throws<ArgumentException>(() => ZoneCommands.Parse(command));
        rules.Zones[0].EnterCommands = Array.Empty<string>();
        string old = rules.Write().Replace(",\"commandsEnabled\":false,\"commandCooldown\":30,\"enterCommands\":[],\"exitCommands\":[]", "");
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(old));
        Assert.False(ZoneRules.Read(old, allowLegacy: true).Zones[0].CommandsEnabled);
    }

    [Fact]
    public void CooldownSeparatesEntryExitAndBlocksCrossZoneTeleportLoops()
    {
        var gate = new ZoneCommandGate();
        Assert.True(gate.TryBegin("Steam_1", "a", true, 30, false, 0));
        Assert.False(gate.TryBegin("Steam_1", "a", true, 30, false, 1));
        Assert.True(gate.TryBegin("Steam_1", "a", false, 30, false, 1));
        Assert.True(gate.TryBegin("Steam_1", "b", true, 30, true, 2));
        Assert.False(gate.TryBegin("Steam_1", "c", true, 30, true, 3));
        Assert.True(gate.TryBegin("Steam_2", "c", true, 30, true, 3));
        Assert.True(gate.TryBegin("Steam_1", "c", true, 30, true, 12));
        Assert.True(gate.TryBegin("Steam_1", "a", true, 30, false, 30));
    }
    private static ZoneRule Safe() => new ZoneRule { Id = "spawn", Name = "Spawn", X1 = -10, Z1 = -10, X2 = 10, Z2 = 10 };

    [Fact]
    public void RoundtripPreservesFlagsMessagesAndNegativeCoordinates()
    {
        var z = Safe(); z.BlockSpawn = 5; z.Despawn = 2; z.Enter = "Welcome \"friend\""; z.NoDamage = true;
        var rules = new ZoneRules { Revision = 42, Zones = new[] { z } };
        var copy = ZoneRules.Read(rules.Write());
        Assert.Equal(rules.Write(), copy.Write());
    }

    [Fact]
    public void SafeZoneDeniesBothDirectionsAndEdgeButNotEnvironment()
    {
        var rules = new ZoneRules { Zones = new[] { Safe() } };
        Assert.True(rules.DenyDamage(10, 10, true, 500, 500));
        Assert.True(rules.DenyDamage(500, 500, true, 0, 0));
        Assert.False(rules.DenyDamage(0, 0, false, 500, 500));
        Assert.False(rules.DenyDamage(11, 10, true, 500, 500));
        rules.Zones[0].NoDamage = true;
        Assert.True(rules.DenyDamage(0, 0, false, 500, 500));
        rules.Zones[0].Enabled = false;
        Assert.False(rules.DenyDamage(0, 0, true, 0, 0));
    }

    [Fact]
    public void SpawnAndDespawnAreIndependentAndNeverTargetOtherCategories()
    {
        var z = Safe(); z.BlockSpawn = 1 | 4; z.Despawn = 2;
        var rules = new ZoneRules { Zones = new[] { z } };
        Assert.True(rules.DenyCreature(0, 0, 1, false));
        Assert.True(rules.DenyCreature(0, 0, 4, false));
        Assert.False(rules.DenyCreature(0, 0, 2, false));
        Assert.False(rules.DenyCreature(0, 0, 1, true));
        Assert.True(rules.DenyCreature(0, 0, 2, true));
        Assert.False(rules.DenyCreature(0, 0, 0, true));
        Assert.False(rules.DenyCreature(0, 0, 7, true));
        Assert.False(rules.DenyCreature(100, 0, 2, true));
    }

    [Fact]
    public void OverlappingPermissiveZoneCannotCancelDeny()
    {
        var safe = Safe(); var open = Safe(); open.NoPvp = false; open.Id = "open";
        var rules = new ZoneRules { Zones = new[] { open, safe } };
        Assert.True(rules.DenyDamage(0, 0, true, 100, 100));
        Array.Reverse(rules.Zones);
        Assert.True(rules.DenyDamage(0, 0, true, 100, 100));
    }

    [Fact]
    public void InvalidOrFutureSettingsDoNotSilentlyDisableProtection()
    {
        var rules = new ZoneRules { Zones = new[] { Safe() } };
        string json = rules.Write();
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(json.Replace("\"noPvp\":true", "\"noPvp\":\"true\"")));
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(json.Replace("\"bonus\":\"none\"", "\"bonus\":\"unknown\"")));
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(json.Replace("\"x2\":10", "\"x2\":-10")));
        rules.Zones = new[] { Safe(), Safe() };
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(rules.Write()));
    }

    [Fact]
    public void NestedInputIsBoundedAndArraysCannotBeSmuggledAsStrings()
    {
        var rules = new ZoneRules();
        string json = rules.Write();
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(json.Replace("\"zones\":[]", "\"zones\":\"[]\"")));
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(json.Replace("\"revision\":0", "\"revision\":0,\"revision\":1")));
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(json.Replace("\"zones\":[]", "\"zones\":[,]")));
        string deep = new string('[', 100) + "0" + new string(']', 100);
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(json.Replace("[]", deep)));
    }
}
