using System;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Json;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class SavedStackChangeTests
{
    private static string Body => "{\"requestId\":\"" + Guid.NewGuid() + "\",\"revision\":\"" + new string('a', 64) + "\",\"section\":\"bag\",\"slot\":0,\"count\":1,\"confirmed\":true,\"operation\":\"replace\",\"itemId\":2,\"itemName\":\"gunPistol\",\"quality\":6}";
    [Fact] public void Replacement_matches_current_native_catalogue_and_quality_rules()
    {
        var change = SavedStackChange.Read("Steam_123", Body);
        Assert.True(change.MatchesItem(2, "gunPistol", 1, true));
        Assert.False(change.MatchesItem(3, "gunPistol", 1, true));
        Assert.False(change.MatchesItem(2, "other", 1, true));
        Assert.False(change.MatchesItem(2, "gunPistol", 1, false));
        change.Quality = 0;
        Assert.False(change.MatchesItem(2, "gunPistol", 1, true));
        Assert.True(change.MatchesItem(2, "gunPistol", 1000, false));
        change.Count = 2;
        Assert.False(change.MatchesItem(2, "gunPistol", 1, false));
    }
    [Theory]
    [InlineData("\"itemId\":2", "\"itemId\":0")]
    [InlineData("\"quality\":6", "\"quality\":7")]
    [InlineData("\"quality\":6", "\"quality\":1.5")]
    [InlineData("\"quality\":6", "\"quality\":null")]
    [InlineData("\"count\":1", "\"count\":0")]
    [InlineData("\"count\":1", "\"count\":1001")]
    [InlineData("\"operation\":\"replace\"", "\"operation\":null")]
    [InlineData("\"operation\":\"replace\",", "")]
    [InlineData("\"quality\":6", "\"other\":6")]
    [InlineData("\"confirmed\":true", "\"confirmed\":false")]
    public void Invalid_or_ambiguous_replacements_never_reach_game(string from, string to) =>
        Assert.Throws<JsonReader.JsonException>(() => SavedStackChange.Read("Steam_123", Body.Replace(from, to)));
}
