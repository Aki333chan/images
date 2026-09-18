using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Json;

namespace Aurum.Companion.Game
{
    internal sealed partial class SdtdGameBridge
    {
        private string? _mapMarkers;
        private World? _mapWorld;
        private readonly Stopwatch _mapAge = Stopwatch.StartNew();
        private string? _pois;
        private World? _poiWorld;
        private readonly Stopwatch _poiAge = Stopwatch.StartNew();
        public string ReadMapPois() => MainThread.Get(() =>
        {
            var world = GameManager.Instance?.World;
            var decorator = world?.ChunkCache?.ChunkProvider?.GetDynamicPrefabDecorator();
            if (decorator == null) { _pois = null; _poiWorld = null; return "{\"ready\":false,\"pois\":[]}"; }
            if (_pois != null && ReferenceEquals(world, _poiWorld) && _poiAge.ElapsedMilliseconds < 60000) return _pois;
            // Native metadata only: no prefab loading, chunk reads or rendering. One copy per minute.
            // ponytail: native API copies the full POI reference list; paginate if huge modded worlds need it.
            var source = new List<PrefabInstance>();
            decorator.GetPOIPrefabs(source);
            var items = new List<string>(); bool truncated = false; int scanned = 0;
            foreach (var instance in source)
            {
                if (++scanned > 4096 || items.Count >= 2048) { truncated = true; break; }
                if (instance?.prefab == null) continue;
                var p = instance.GetPOIMetadata();
                string name = p.prefabName ?? "";
                if (name.Length > 64) name = name.Substring(0, 64);
                items.Add(JsonWriter.Object(new[] {
                    F("id", JsonWriter.Number(instance.id)), F("name", JsonWriter.String(name)),
                    F("x", JsonWriter.Coordinate(p.position.x + p.size.x / 2f)),
                    F("z", JsonWriter.Coordinate(p.position.z + p.size.z / 2f)),
                    F("tier", JsonWriter.Number(p.tier)), F("trader", JsonWriter.Bool(p.traderArea))
                }));
            }
            _pois = JsonWriter.Object(new[] { F("ready", "true"), F("pois", JsonWriter.Array(items)), F("truncated", JsonWriter.Bool(truncated)) });
            _poiWorld = world; _poiAge.Restart(); return _pois;
        });
        private NativeMapFiles? MapFiles() => MainThread.Get(() => GameManager.Instance?.World == null ? null : new NativeMapFiles(Path.Combine(GameIO.GetSaveGameDir(), "map")));
        public string ReadMapInfo() => MapFiles()?.Info() ?? "{\"available\":false,\"reason\":\"world_loading\"}";
        public string ReadMapTile(int zoom, int x, int z) => MapFiles()?.Tile(zoom, x, z) ?? "{\"png\":null}";
        public string ReadMapMarkers() => MainThread.Get(() =>
        {
            var manager = GameManager.Instance;
            var world = manager?.World;
            if (world == null) { _mapMarkers = null; _mapWorld = null; return "{\"ready\":false,\"players\":[],\"claims\":[]}"; }
            if (_mapMarkers != null && ReferenceEquals(world, _mapWorld) && _mapAge.ElapsedMilliseconds < 5000) return _mapMarkers;
            var players = new List<string>(); var claims = new List<string>(); bool truncated = false;
            foreach (var p in OnlinePlayers())
            {
                if (players.Count == 256) { truncated = true; break; }
                players.Add(JsonWriter.Object(new[] { F("id", JsonWriter.String(p.PlayerId)), F("name", JsonWriter.String(Short(p.Name))), F("x", JsonWriter.Coordinate(p.X)), F("z", JsonWriter.Coordinate(p.Z)) }));
            }
            int scanned = 0;
            int size = Math.Max(1, Math.Min(1024, GameStats.GetInt(EnumGameStats.LandClaimSize)));
            if (manager?.persistentPlayers?.Players != null)
            foreach (var entry in manager.persistentPlayers.Players)
            {
                if (++scanned > 4096 || claims.Count >= 2048) { truncated = true; break; }
                var owner = entry.Value; var blocks = owner.GetLandProtectionBlocks();
                if (blocks == null) continue;
                foreach (var pos in blocks)
                {
                    if (claims.Count >= 2048) { truncated = true; break; }
                    claims.Add(JsonWriter.Object(new[] { F("ownerId", JsonWriter.String(owner.PrimaryId?.CombinedString)), F("owner", JsonWriter.String(Short(owner.PlayerName?.DisplayName ?? ""))), F("x", JsonWriter.Number(pos.x)), F("z", JsonWriter.Number(pos.z)), F("size", JsonWriter.Number(size)) }));
                }
            }
            _mapMarkers = JsonWriter.Object(new[] { F("ready", "true"), F("players", JsonWriter.Array(players)), F("claims", JsonWriter.Array(claims)), F("truncated", JsonWriter.Bool(truncated)) });
            _mapWorld = world; _mapAge.Restart(); return _mapMarkers;
        });
        private static string Short(string s) => s.Length > 80 ? s.Substring(0, 80) : s;
        private static KeyValuePair<string,string> F(string k, string v) => new KeyValuePair<string,string>(k,v);
    }
}
