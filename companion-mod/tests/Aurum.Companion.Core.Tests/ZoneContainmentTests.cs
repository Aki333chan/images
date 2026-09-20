using System;
using System.Collections.Generic;
using Aurum.Companion.Core.Game;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class ZoneContainmentTests
{
    private static ZoneRule Zone(string mode) => new ZoneRule {
        Id = mode, Name = mode, Type = mode, X1 = -10, X2 = 10, Z1 = -10, Z2 = 10,
        Movement = new ZoneMovement { Mode = mode }
    };
    [Fact]
    public void PrisonPersistsReconnectDeathAndRoundtripAndExpiresAtExactUtcDeadline()
    {
        var prison = Zone("prison");
        prison.Movement.Sentences = new[] { new ZoneSentence { Player = "Steam_1", Until = 200 } };
        var rules = ZoneRules.Read(new ZoneRules { Zones = new[] { prison } }.Write());
        var state = new ZoneContainment();
        Assert.Equal("prison", state.Select(rules, 1, new[] { "EOS_alias", "Steam_1" }, 500, 500, null, 199)?.Id);
        state.Remove(1); // Death/logout removes only ephemeral state.
        Assert.Equal("prison", state.Select(rules, 2, new[] { "Steam_1" }, 500, 500, null, 199)?.Id);
        Assert.Null(state.Select(rules, 2, new[] { "Steam_1" }, 500, 500, null, 200));
        rules.Zones[0].Movement.Sentences[0].Until = 0;
        Assert.NotNull(new ZoneContainment().Select(rules, 3, new[] { "Steam_1" }, 500, 500, null, 9999999));
        rules.Zones[0].Movement.Sentences = Array.Empty<ZoneSentence>();
        Assert.Null(state.Select(rules, 3, new[] { "Steam_1" }, 500, 500, null, 1));
    }
    [Fact]
    public void EventRequiresExplicitListAndObservedEntryAndReleasesOnDeathLogoutDisableRemoval()
    {
        var zone = Zone("event");
        var rules = new ZoneRules { Zones = new[] { zone } };
        var state = new ZoneContainment(); var empty = new HashSet<string>(); var id = new[] { "Steam_1" };
        Assert.Null(state.Select(rules, 1, id, 0, 0, empty, 1)); // Empty is nobody, never everyone.
        zone.Movement.Players = id;
        Assert.Null(state.Select(rules, 1, id, 0, 0, null, 1)); // Login or save is not entry.
        Assert.NotNull(state.Select(rules, 1, id, 0, 0, empty, 1));
        Assert.NotNull(state.Select(rules, 1, id, 100, 100, new HashSet<string> { "event" }, 1));
        state.Remove(1);
        Assert.Null(state.Select(rules, 1, id, 100, 100, null, 1));
        Assert.NotNull(state.Select(rules, 1, id, 0, 0, empty, 1));
        zone.Enabled = false;
        Assert.Null(state.Select(rules, 1, id, 100, 100, empty, 1));
        zone.Enabled = true;
        Assert.Null(state.Select(rules, 1, id, 100, 100, empty, 1));
        Assert.NotNull(state.Select(rules, 1, id, 0, 0, empty, 1));
        zone.Movement.Players = Array.Empty<string>();
        Assert.Null(state.Select(rules, 1, id, 100, 100, empty, 1));
        state.Retain(new HashSet<int>());
        state.Clear();
    }
    [Fact]
    public void PrisonOverridesEventAndAliasConflictsResolveByPriorityThenId()
    {
        var prison = Zone("prison"); var ev = Zone("event");
        ev.Movement.Players = new[] { "Steam_1" }; ev.Movement.Priority = 1000;
        prison.Movement.Sentences = new[] { new ZoneSentence { Player = "Steam_1" } };
        var rules = new ZoneRules { Zones = new[] { ev, prison } };
        var state = new ZoneContainment();
        Assert.Equal("prison", state.Select(rules, 1, new[] { "Steam_1" }, 0, 0, new HashSet<string>(), 1)?.Id);
        prison.Movement.Sentences = Array.Empty<ZoneSentence>();
        Assert.Null(state.Select(rules, 1, new[] { "Steam_1" }, 100, 100, new HashSet<string> { "event" }, 1));
        prison.Movement.Sentences = new[] { new ZoneSentence { Player = "Steam_1" } };
        var alias = Zone("prison"); alias.Id = "alias";
        alias.Movement.Sentences = new[] { new ZoneSentence { Player = "EOS_1" } };
        rules.Zones = new[] { prison, alias };
        Assert.Equal("alias", state.Select(rules, 1, new[] { "Steam_1", "EOS_1" }, 100, 100, null, 1)?.Id);
        Array.Reverse(rules.Zones);
        Assert.Equal("alias", state.Select(rules, 1, new[] { "Steam_1", "EOS_1" }, 100, 100, null, 1)?.Id);
        prison.Movement.Priority = 1;
        Assert.Equal("prison", state.Select(rules, 1, new[] { "Steam_1", "EOS_1" }, 100, 100, null, 1)?.Id);
    }
    [Fact]
    public void DisablingEventBetweenTicksReleasesMembershipImmediately()
    {
        var ev = Zone("event"); ev.Movement.Players = new[] { "Steam_1" };
        var rules = new ZoneRules { Zones = new[] { ev } }; var state = new ZoneContainment();
        Assert.NotNull(state.Select(rules, 1, new[] { "Steam_1" }, 0, 0, new HashSet<string>(), 1));
        state.RulesChanged(rules); // A name-only save must preserve membership.
        Assert.NotNull(state.Select(rules, 1, new[] { "Steam_1" }, 100, 100, null, 1));
        ev.Enabled = false; state.RulesChanged(rules);
        ev.Enabled = true; state.RulesChanged(rules);
        Assert.Null(state.Select(rules, 1, new[] { "Steam_1" }, 100, 100, null, 1));
    }
    [Fact]
    public void ValidationRequiresInternalPointUniqueAssignmentsAndStrictNewSchema()
    {
        var prison = Zone("prison"); var rules = new ZoneRules { Zones = new[] { prison } };
        prison.Movement.Sentences = new[] { new ZoneSentence { Player = "Steam_1", Until = 500 } };
        Assert.Equal(rules.Write(), ZoneRules.Read(rules.Write()).Write());
        prison.Movement.X = 500;
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(rules.Write()));
        prison.Movement.X = 0;
        prison.Movement.Sentences[0].Until = -1;
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(rules.Write()));
        prison.Movement.Sentences[0].Until = 0;
        var duplicate = Zone("prison"); duplicate.Id = "other";
        duplicate.Movement.Sentences = prison.Movement.Sentences;
        rules.Zones = new[] { prison, duplicate };
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(rules.Write()));
        rules.Zones = new[] { prison };
        prison.Movement.Sentences = Array.Empty<ZoneSentence>();
        string legacy = rules.Write().Replace(",\"dismount\":false,\"kickOnFailure\":false,\"sentences\":[]", "");
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(legacy));
        Assert.False(ZoneRules.Read(legacy, true).Zones[0].Movement.KickOnFailure);
    }
}
