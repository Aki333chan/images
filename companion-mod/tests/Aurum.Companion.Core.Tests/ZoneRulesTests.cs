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
    public void MovementCombinesIdsAndLevelsAndDenialWinsDeterministically()
    {
        var low = Safe(); low.Id = "a"; low.Movement = new ZoneMovement { Mode = "restricted", MinLevel = 10, MaxLevel = 50, Players = new[] { "Steam_123" }, X = 100 };
        var high = Safe(); high.Id = "b"; high.Movement = new ZoneMovement { Mode = "restricted", MinLevel = 30, Priority = 5, X = 100 };
        var portal = Safe(); portal.Id = "portal"; portal.Movement = new ZoneMovement { Mode = "portal", Priority = 1000, X = 100 };
        var rules = new ZoneRules { Zones = new[] { portal, low, high } };
        Assert.False(low.Movement.Allows(9, new[] { "Steam_123" }));
        Assert.False(low.Movement.Allows(51, new[] { "Steam_123" }));
        Assert.False(low.Movement.Allows(20, new[] { "Steam_999" }));
        Assert.True(low.Movement.Allows(20, new[] { "EOS_other", "Steam_123" }));
        Assert.Equal("b", ZoneMovementPolicy.Select(rules, 0, 0, 20, new[] { "Steam_123" }, null)?.Id);
        Array.Reverse(rules.Zones);
        Assert.Equal("b", ZoneMovementPolicy.Select(rules, 0, 0, 20, new[] { "Steam_123" }, null)?.Id);
        high.Movement.Priority = 0;
        Assert.Equal("a", ZoneMovementPolicy.Select(rules, 0, 0, 1, new[] { "Steam_123" }, null)?.Id);
        Assert.Null(ZoneMovementPolicy.Select(rules, 0, 0, 40, new[] { "Steam_123" }, null));
        Assert.Equal("portal", ZoneMovementPolicy.Select(rules, 0, 0, 40, new[] { "Steam_123" }, new System.Collections.Generic.HashSet<string>())?.Id);
        Assert.Null(ZoneMovementPolicy.Select(rules, 0, 0, 40, new[] { "Steam_123" }, new System.Collections.Generic.HashSet<string> { "portal" }));
        Assert.Null(ZoneMovementPolicy.Select(rules, 1000, 1000, 1, Array.Empty<string>(), null));
    }

    [Fact]
    public void MovementRejectsUnsafeConfigAndLoadsOldFilesOnlyExplicitly()
    {
        var zone = Safe(); var rules = new ZoneRules { Zones = new[] { zone } };
        const string movement = ",\"movement\":{\"mode\":\"none\",\"priority\":0,\"minLevel\":0,\"maxLevel\":0,\"players\":[],\"x\":0,\"y\":65,\"z\":0,\"cooldown\":10,\"message\":\"\",\"dismount\":false,\"kickOnFailure\":false,\"sentences\":[]}";
        string legacy = rules.Write().Replace(movement, "");
        Assert.NotEqual(rules.Write(), legacy);
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(legacy));
        Assert.Equal("none", ZoneRules.Read(legacy, true).Zones[0].Movement.Mode);
        zone.Movement.Mode = "restricted";
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(rules.Write())); // Return is inside its own restriction.
        zone.Movement.X = 100;
        Assert.Equal(rules.Write(), ZoneRules.Read(rules.Write()).Write());
        foreach (int y in new[] { -1, 0, 252 }) {
            zone.Movement.Y = y;
            Assert.ThrowsAny<Exception>(() => ZoneRules.Read(rules.Write()));
        }
        zone.Movement.Y = 65; zone.Movement.Players = new[] { "Alice" };
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(rules.Write()));
        zone.Movement.Players = new[] { "Steam_123", "Steam_123" };
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(rules.Write()));
        zone.Movement.Players = Array.Empty<string>();
        var other = Safe(); other.Id = "destination"; other.X1 = 90; other.X2 = 110; other.Movement.Mode = "portal"; other.Movement.X = 1000;
        rules.Zones = new[] { zone, other };
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(rules.Write())); // Cross-zone chain.
        other.Enabled = false;
        Assert.Equal(rules.Write(), ZoneRules.Read(rules.Write()).Write());
    }

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
