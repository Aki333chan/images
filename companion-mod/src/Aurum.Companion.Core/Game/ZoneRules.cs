using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using Aurum.Companion.Core.Json;

namespace Aurum.Companion.Core.Game
{
    // All coordinates are world X/Z. Bounds include the edges and the full world height.
    public sealed class ZoneRule
    {
        public string Id = "", Name = "", Type = "safe", Enter = "", Exit = "";
        public bool Enabled = true, NoPvp = true, NoDamage;
        public double X1, Z1, X2, Z2;
        public int BlockSpawn, Despawn;
        public string Bonus = "none";
        public bool Contains(double x, double z) => Enabled &&
            x >= X1 && x <= X2 && z >= Z1 && z <= Z2;
    }

    public sealed class ZoneRules
    {
        public const int MaxZones = 100;
        public long Revision;
        public string WorldId = Guid.NewGuid().ToString("N");
        public ZoneRule[] Zones = Array.Empty<ZoneRule>();

        public static ZoneRules Read(string json)
        {
            if (System.Text.Encoding.UTF8.GetByteCount(json) > 60000) throw Invalid();
            var root = JsonReader.ParseObject(json);
            Keys(root, "revision", "worldId", "zones");
            var result = new ZoneRules { Revision = (long)Number(root, "revision", 0, 9007199254740990, true), WorldId = Text(root, "worldId", 32) };
            if (!Regex.IsMatch(result.WorldId, "\\A[a-f0-9]{32}\\z") || !root.TryGetValue("zones", out var value) || !(value is List<object?> rows) || rows.Count > MaxZones)
                throw Invalid();
            var ids = new HashSet<string>(StringComparer.Ordinal);
            var zones = new List<ZoneRule>();
            foreach (var row in rows)
            {
                if (!(row is Dictionary<string, object?> map)) throw Invalid();
                Keys(map, "id", "name", "type", "enabled", "x1", "z1", "x2", "z2", "noPvp", "noDamage", "blockSpawn", "despawn", "enter", "exit", "bonus");
                var z = new ZoneRule {
                    Id = Text(map, "id", 48), Name = Text(map, "name", 80), Type = Text(map, "type", 24),
                    Enabled = Boolean(map, "enabled"), NoPvp = Boolean(map, "noPvp"), NoDamage = Boolean(map, "noDamage"),
                    X1 = Number(map, "x1", -500000, 500000), Z1 = Number(map, "z1", -500000, 500000),
                    X2 = Number(map, "x2", -500000, 500000), Z2 = Number(map, "z2", -500000, 500000),
                    BlockSpawn = (int)Number(map, "blockSpawn", 0, 7, true), Despawn = (int)Number(map, "despawn", 0, 7, true),
                    Enter = Text(map, "enter", 240), Exit = Text(map, "exit", 240), Bonus = Text(map, "bonus", 24)
                };
                if (!Regex.IsMatch(z.Id, "\\A[a-z0-9][a-z0-9_-]{0,47}\\z") || !ids.Add(z.Id) ||
                    string.IsNullOrWhiteSpace(z.Name) || z.X1 >= z.X2 || z.Z1 >= z.Z2 ||
                    !new[] { "safe", "information", "sanctuary", "bonus", "custom" }.Contains(z.Type) ||
                    !new[] { "none", "regeneration", "stamina", "speed" }.Contains(z.Bonus)) throw Invalid();
                zones.Add(z);
            }
            result.Zones = zones.ToArray();
            return result;
        }

        // Deny wins overlaps; an attacker in a safe zone must not shoot out of it either.
        public bool DenyDamage(double victimX, double victimZ, bool pvp, double attackerX, double attackerZ)
        {
            foreach (var z in Zones)
            {
                if (z.Contains(victimX, victimZ) && (z.NoDamage || (pvp && z.NoPvp))) return true;
                if (pvp && z.NoPvp && z.Contains(attackerX, attackerZ)) return true;
            }
            return false;
        }

        // Bits: 1 zombies, 2 peaceful animals, 4 hostile animals. No player/trader/vehicle bit.
        public bool DenyCreature(double x, double z, int category, bool existing)
        {
            if (category != 1 && category != 2 && category != 4) return false;
            foreach (var zone in Zones)
                if (zone.Contains(x, z) && (((existing ? zone.Despawn : zone.BlockSpawn) & category) != 0)) return true;
            return false;
        }

        public string Write() => JsonWriter.Object(new[] {
            Pair("revision", JsonWriter.Number(Revision)), Pair("worldId", JsonWriter.String(WorldId)), Pair("zones", JsonWriter.Array(Zones.Select(z => JsonWriter.Object(new[] {
                Pair("id", JsonWriter.String(z.Id)), Pair("name", JsonWriter.String(z.Name)), Pair("type", JsonWriter.String(z.Type)),
                Pair("enabled", JsonWriter.Bool(z.Enabled)), Pair("x1", JsonWriter.Number(z.X1)), Pair("z1", JsonWriter.Number(z.Z1)),
                Pair("x2", JsonWriter.Number(z.X2)), Pair("z2", JsonWriter.Number(z.Z2)),
                Pair("noPvp", JsonWriter.Bool(z.NoPvp)), Pair("noDamage", JsonWriter.Bool(z.NoDamage)),
                Pair("blockSpawn", JsonWriter.Number(z.BlockSpawn)), Pair("despawn", JsonWriter.Number(z.Despawn)),
                Pair("enter", JsonWriter.String(z.Enter)), Pair("exit", JsonWriter.String(z.Exit)), Pair("bonus", JsonWriter.String(z.Bonus))
            })))) });

        private static KeyValuePair<string, string> Pair(string key, string value) => new KeyValuePair<string, string>(key, value);
        private static JsonReader.JsonException Invalid() => new JsonReader.JsonException("invalid_zones");
        private static void Keys(Dictionary<string, object?> map, params string[] keys)
        {
            if (map.Count != keys.Length || keys.Any(k => !map.ContainsKey(k))) throw Invalid();
        }
        private static string Text(Dictionary<string, object?> map, string key, int limit)
        {
            if (!map.TryGetValue(key, out var value) || !(value is string text) || text.Length > limit || text.Any(char.IsControl)) throw Invalid();
            return text;
        }
        private static bool Boolean(Dictionary<string, object?> map, string key)
        {
            if (!map.TryGetValue(key, out var value) || !(value is bool b)) throw Invalid();
            return b;
        }
        private static double Number(Dictionary<string, object?> map, string key, double min, double max, bool integer = false)
        {
            if (!map.TryGetValue(key, out var value) || !(value is double n) || double.IsNaN(n) || double.IsInfinity(n) || n < min || n > max || (integer && n != Math.Truncate(n))) throw Invalid();
            return n;
        }
    }
}
