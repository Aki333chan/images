using System;
using System.Collections.Generic;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Http;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class MapRouterTests
{
    private sealed class Bridge : IGameBridge, IMapBridge, IInventoryBridge
    {
        public int Reads;
        public string ReadInventory(string id) { Reads++; return "{\"available\":false,\"reason\":\"snapshot_pending\"}"; }
        public string ReadMapInfo() { Reads++; return "{\"available\":true}"; }
        public string ReadMapMarkers() { Reads++; return "{\"ready\":true}"; }
        public string ReadMapPois() { Reads++; return "{\"ready\":true,\"pois\":[]}"; }
        public string ReadMapTile(int zoom, int x, int z) { Reads++; return "{\"png\":null}"; }
        public bool SendPrivateMessage(string id, string text) => false;
        public void Broadcast(string text) { }
        public IReadOnlyList<OnlinePlayer> OnlinePlayers() => Array.Empty<OnlinePlayer>();
        public OnlinePlayer? FindPlayer(string id) => null;
        public WorldState ReadWorldState() => new();
        public void Log(string text) { }
        public void LogError(string text, Exception? error) { }
    }
    [Theory]
    [InlineData("/map/info")]
    [InlineData("/map/markers")]
    [InlineData("/map/pois")]
    [InlineData("/players/Steam_76561190000000000/inventory")]
    [InlineData("/map/tile/4/-1/-2")]
    public void Map_requires_token_and_dispatches_only_authorized_get(string path)
    {
        var bridge = new Bridge(); var router = new CompanionRouter(bridge, "test-token-long-enough", "test");
        Assert.Equal(401, router.Handle(new("GET", path, ""), null).Status);
        Assert.Equal(0, bridge.Reads);
        Assert.Equal(200, router.Handle(new("GET", path, ""), "test-token-long-enough").Status);
        Assert.Equal(1, bridge.Reads);
        Assert.Equal(404, router.Handle(new("POST", path, ""), "test-token-long-enough").Status);
        Assert.Contains("map-read", router.Handle(new("GET", "/ping", ""), "test-token-long-enough").Json);
    }
    [Theory]
    [InlineData("/map/tile/9/0/0")]
    [InlineData("/map/tile/4/65537/0")]
    [InlineData("/map/tile/4/nope/0")]
    public void Invalid_tile_request_never_reaches_bridge(string path)
    {
        var bridge = new Bridge(); var router = new CompanionRouter(bridge, "test-token-long-enough", "test");
        Assert.Equal(400, router.Handle(new("GET", path, ""), "test-token-long-enough").Status);
        Assert.Equal(0, bridge.Reads);
    }
    [Theory]
    [InlineData("Alice")]
    [InlineData("Steam_1%2F..")]
    [InlineData("Steam_1%0A")]
    [InlineData("Steam_1%3Fadmin")]
    public void Invalid_inventory_target_never_reaches_game(string id)
    {
        var bridge = new Bridge(); var router = new CompanionRouter(bridge, "test-token-long-enough", "test");
        Assert.Equal(400, router.Handle(new("GET", "/players/" + id + "/inventory", ""), "test-token-long-enough").Status);
        Assert.Equal(0, bridge.Reads);
        Assert.Contains("inventory-read", router.Handle(new("GET", "/ping", ""), "test-token-long-enough").Json);
    }
}
