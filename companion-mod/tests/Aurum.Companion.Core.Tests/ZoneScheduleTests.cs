using System;
using System.Collections.Generic;
using Aurum.Companion.Core.Game;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class ZoneScheduleTests
{
    private static long At(string text) => DateTimeOffset.Parse(text, System.Globalization.CultureInfo.InvariantCulture).ToUnixTimeSeconds();
    private static ZoneRule Zone() => new ZoneRule { Id = "stage", Name = "Stage", X1 = -10, X2 = 10, Z1 = -10, Z2 = 10 };
    [Fact]
    public void OptInDatesUseInclusiveStartExclusiveEndAndDoNotRestartTheirDuration()
    {
        long start = At("2026-09-21T10:00:00Z");
        var s = new ZoneSchedule { Start = start, End = start + 60, Days = 0 };
        Assert.True(s.Allows(start - 9999));
        s.Enabled = true; s.Days = 127;
        Assert.False(s.Allows(start - 1)); Assert.True(s.Allows(start));
        Assert.True(s.Allows(start + 59)); Assert.False(s.Allows(start + 60));
        var zone = Zone(); zone.Schedule = s;
        var rules = ZoneRules.Read(new ZoneRules { Zones = new[] { zone } }.Write());
        rules.RefreshSchedules(start + 100);
        Assert.False(rules.Zones[0].IsActive);
    }
    [Theory]
    [InlineData("2026-09-21T21:59:59Z", false)]
    [InlineData("2026-09-21T22:00:00Z", true)]
    [InlineData("2026-09-22T01:59:59Z", true)]
    [InlineData("2026-09-22T02:00:00Z", false)]
    [InlineData("2026-09-22T22:00:00Z", false)]
    public void OvernightIntervalBelongsToItsStartingDay(string time, bool expected)
    {
        var s = new ZoneSchedule { Enabled = true, Days = 1, FromMinute = 22 * 60, ToMinute = 120 };
        Assert.Equal(expected, s.Allows(At(time)));
    }
    [Fact]
    public void WeekWrapAndQuarterHourOffsetsWorkWithoutLocalTimeOrDst()
    {
        var s = new ZoneSchedule { Enabled = true, Days = 64, FromMinute = 23 * 60, ToMinute = 60, OffsetMinutes = 345 };
        Assert.True(s.Allows(At("2026-09-20T18:30:00Z"))); // Monday 00:15 at UTC+05:45, Sunday start.
        Assert.False(s.Allows(At("2026-09-20T19:15:00Z")));
        s.OffsetMinutes = -720;
        Assert.True(s.Allows(At("2026-09-21T12:15:00Z")));
    }
    [Fact]
    public void EqualTimesMeanWholeSelectedDayAndEmptyDaysMeanNever()
    {
        var s = new ZoneSchedule { Enabled = true, Days = 1, FromMinute = 123, ToMinute = 123 };
        Assert.True(s.Allows(At("2026-09-21T00:00:00Z")));
        Assert.True(s.Allows(At("2026-09-21T23:59:59Z")));
        Assert.False(s.Allows(At("2026-09-22T00:00:00Z")));
        s.Days = 0; Assert.False(s.Allows(At("2026-09-21T00:00:00Z")));
        s.Days = 1; s.FromMinute = 600; s.ToMinute = 720;
        Assert.False(s.Allows(At("2026-09-21T09:59:59Z")));
        Assert.True(s.Allows(At("2026-09-21T10:00:00Z")));
        Assert.False(s.Allows(At("2026-09-21T12:00:00Z")));
    }
    [Fact]
    public void InactiveRulesDoNotProtectOrMovePlayersAndCloseEvents()
    {
        var zone = Zone(); zone.Movement.Mode = "event"; zone.Movement.Players = new[] { "Steam_1" };
        zone.BlockSpawn = 7; zone.Schedule = new ZoneSchedule { Enabled = true, End = 200 };
        var rules = new ZoneRules { Zones = new[] { zone } }; var state = new ZoneContainment();
        Assert.Empty(rules.RefreshSchedules(100));
        Assert.True(rules.DenyDamage(0, 0, true, 20, 20));
        Assert.NotNull(state.Select(rules, 1, new[] { "Steam_1" }, 0, 0, new HashSet<string>(), 100));
        Assert.Equal(new[] { "stage" }, rules.RefreshSchedules(200));
        state.RulesChanged(rules);
        Assert.False(rules.DenyDamage(0, 0, true, 20, 20));
        Assert.False(rules.DenyCreature(0, 0, 1, false));
        Assert.Null(state.Select(rules, 1, new[] { "Steam_1" }, 100, 100, null, 200));
        Assert.Empty(rules.RefreshSchedules(201));
        // Destination geometry remains valid/blocked regardless of current clock state.
        Assert.True(ZoneMovementPolicy.DestinationClear(rules, zone.Movement));
        var portal = Zone(); portal.Id = "portal"; portal.X1 = 20; portal.X2 = 40;
        portal.Movement.Mode = "portal"; rules.Zones = new[] { zone, portal };
        Assert.False(ZoneMovementPolicy.DestinationClear(rules, portal.Movement));
    }
    [Fact]
    public void ParserRejectsInvalidScheduleAndPrisonSchedulesAndReadsLegacyOnlyExplicitly()
    {
        var zone = Zone(); var rules = new ZoneRules { Zones = new[] { zone } };
        const string schedule = ",\"schedule\":{\"enabled\":false,\"start\":0,\"end\":0,\"offsetMinutes\":0,\"days\":127,\"fromMinute\":0,\"toMinute\":0}";
        string old = rules.Write().Replace(schedule, ""); Assert.NotEqual(old, rules.Write());
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(old));
        Assert.False(ZoneRules.Read(old, true).Zones[0].Schedule.Enabled);
        foreach (Action<ZoneSchedule> change in new Action<ZoneSchedule>[] {
            s => s.OffsetMinutes = 1, s => s.Days = 128, s => s.FromMinute = 1440,
            s => s.ToMinute = -1, s => { s.Start = 200; s.End = 199; }, s => s.Start = -1 })
        {
            zone.Schedule = new ZoneSchedule(); change(zone.Schedule);
            Assert.ThrowsAny<Exception>(() => ZoneRules.Read(rules.Write()));
        }
        zone.Schedule = new ZoneSchedule { Enabled = true }; zone.Movement.Mode = "prison";
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(rules.Write()));
    }
}
