using System;
using System.Collections.Generic;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Http;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class MapRouterTests
{
    [Fact]
    public void Grant_route_requires_token_and_replays_without_repeating_game_write()
    {
        const string token = "test-token-long-enough";
        var bridge = new Bridge(); var router = new CompanionRouter(bridge, token, "test");
        var catalogue = Aurum.Companion.Core.Json.JsonReader.ParseObject(router.Handle(new("GET", "/items/ammo", ""), token).Json);
        var session = Aurum.Companion.Core.Json.JsonReader.StringOrNull(catalogue, "sessionId");
        var body = "{\"sessionId\":\"" + session + "\",\"requestId\":\"" + Guid.NewGuid() + "\",\"itemId\":1,\"itemName\":\"test\",\"count\":1,\"quality\":0,\"confirmed\":true}";
        var request = new HttpRequestData("POST", "/players/Steam_123/item-drop", body);
        Assert.Equal(401, router.Handle(request, null).Status);
        Assert.Equal(0, bridge.Reads);
        Assert.Contains("spawned", router.Handle(request, token).Json);
        Assert.Contains("spawned", router.Handle(request, token).Json);
        Assert.Equal(1, bridge.Reads);
        Assert.Equal(400, router.Handle(new("POST", "/players/Alice/item-drop", body), token).Status);
        Assert.Contains("session_expired", new CompanionRouter(bridge, token, "test").Handle(request, token).Json);
        Assert.Equal(1, bridge.Reads);
    }
    private sealed class Bridge : IGameBridge, IMapBridge, IInventoryBridge, ISavedInventoryBridge
    {
        public int Reads;
        public string SavedPlayers(string query, int offset) { Reads++; return "{\"ready\":true,\"players\":[],\"hasMore\":false,\"truncated\":false}"; }
        public string ReadSavedInventory(string id) { Reads++; return "{\"available\":false,\"source\":\"saved_file\",\"reason\":\"save_missing\"}"; }
        public string SearchItems(string query) => "{\"ready\":true,\"items\":[],\"truncated\":false}";
        public string DropItem(ItemGrant request) { Reads++; return "spawned"; }
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
    [InlineData("/players/Steam_76561190000000000/saved-inventory")]
    [InlineData("/saved-players/_/0")]
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
    [InlineData("/saved-players/_/-1")]
    [InlineData("/saved-players/_/10001")]
    [InlineData("/players/Steam_1%2F../saved-inventory")]
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
