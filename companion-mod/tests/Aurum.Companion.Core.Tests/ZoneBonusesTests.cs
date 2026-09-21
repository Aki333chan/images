using System;
using Aurum.Companion.Core.Game;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class ZoneBonusesTests
{
    private const string Empty = "\"bonuses\":{\"regeneration\":0,\"stamina\":0,\"speed\":0}";
    private static ZoneRule Zone() => new ZoneRule { Id = "a", Name = "A", X2 = 10, Z2 = 10 };
    private static string Json() => new ZoneRules { Zones = new[] { Zone() } }.Write();

    [Theory]
    [InlineData("none", 0, 0, 0)]
    [InlineData("regeneration", 1, 0, 0)]
    [InlineData("stamina", 0, 3, 0)]
    [InlineData("speed", 0, 0, 15)]
    public void LegacyFilePreservesStrengthButHttpRejectsOldShape(string old, double health, double stamina, double speed)
    {
        string json = Json().Replace(Empty, "\"bonus\":\"" + old + "\"");
        Assert.NotEqual(Json(), json);
        Assert.ThrowsAny<Exception>(() => ZoneRules.Read(json));
        var parsed = ZoneRules.Read(json, true);
        var b = parsed.Zones[0].Bonuses;
        Assert.Equal(health, b.Regeneration); Assert.Equal(stamina, b.Stamina); Assert.Equal(speed, b.Speed);
        Assert.Equal(parsed.Write(), ZoneRules.Read(parsed.Write()).Write());
    }

    [Theory]
    [InlineData("\"bonus\":\"unknown\"")]
    [InlineData("\"bonuses\":{}")]
    [InlineData("\"bonuses\":{\"regeneration\":-1,\"stamina\":0,\"speed\":0}")]
    [InlineData("\"bonuses\":{\"regeneration\":11,\"stamina\":0,\"speed\":0}")]
    [InlineData("\"bonuses\":{\"regeneration\":0,\"stamina\":31,\"speed\":0}")]
    [InlineData("\"bonuses\":{\"regeneration\":0,\"stamina\":0,\"speed\":101}")]
    [InlineData("\"bonuses\":{\"regeneration\":0,\"stamina\":0,\"speed\":\"15\"}")]
    [InlineData(Empty + ",\"bonus\":\"none\"")]
    public void RejectsMalformedOrAmbiguousBonusEvenOnLegacyRead(string replacement)
    {
        foreach (bool legacy in new[] { false, true })
            Assert.ThrowsAny<Exception>(() => ZoneRules.Read(Json().Replace(Empty, replacement), legacy));
    }

    [Fact]
    public void IndependentMaximumIgnoresDisabledAndInactiveZonesAndNeverStacks()
    {
        var a = Zone(); a.Bonuses = new ZoneBonuses { Regeneration = 1.5, Stamina = 3, Speed = 15 };
        var b = Zone(); b.Bonuses = new ZoneBonuses { Regeneration = 1, Stamina = 6, Speed = 10 };
        var off = Zone(); off.Enabled = false; off.Bonuses = new ZoneBonuses { Regeneration = 10, Speed = 100 };
        var timed = Zone(); timed.ScheduleActive = false; timed.Bonuses = new ZoneBonuses { Stamina = 30 };
        var zones = new[] { a, b, off, timed };
        var result = ZoneBonuses.Strongest(zones);
        Assert.Equal(1.5, result.Regeneration); Assert.Equal(6, result.Stamina); Assert.Equal(15, result.Speed);
        Assert.Equal(0.15f, result.NativeValue(3)); Assert.Equal(6f, result.NativeValue(2));
        Array.Reverse(zones); Assert.Equal(result, ZoneBonuses.Strongest(zones));
        Assert.Equal(default, ZoneBonuses.Strongest(Array.Empty<ZoneRule>()));
        var rules = new ZoneRules { Zones = new[] { a } };
        Assert.Equal(rules.Write(), ZoneRules.Read(rules.Write()).Write());
    }
}
