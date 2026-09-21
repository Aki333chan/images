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
        public bool NoCreatureBlockDamage, NoExplosionBlockDamage;
        public double X1, Z1, X2, Z2;
        public int BlockSpawn, Despawn;
        public string Bonus = "none";
        public bool CommandsEnabled;
        public int CommandCooldown = 30;
        public string[] EnterCommands = Array.Empty<string>(), ExitCommands = Array.Empty<string>();
        public ZoneMovement Movement = new ZoneMovement();
        public ZoneSchedule Schedule = new ZoneSchedule();
        // Runtime-only snapshot, refreshed in the existing game-thread tick. Never serialized.
        public bool ScheduleActive = true;
        public bool IsActive => Enabled && ScheduleActive;
        public bool Contains(double x, double z) => IsActive && InBounds(x, z);
        public bool InBounds(double x, double z) =>
            x >= X1 && x <= X2 && z >= Z1 && z <= Z2;
    }

    public sealed class ZoneRules
    {
        public const int MaxZones = 100;
        public long Revision;
        public string WorldId = Guid.NewGuid().ToString("N");
        public ZoneRule[] Zones = Array.Empty<ZoneRule>();

        public static ZoneRules Read(string json, bool allowLegacy = false)
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
                if (allowLegacy && !map.ContainsKey("commandsEnabled") && !map.ContainsKey("commandCooldown") && !map.ContainsKey("enterCommands") && !map.ContainsKey("exitCommands"))
                {
                    map["commandsEnabled"] = false; map["commandCooldown"] = 30d;
                    map["enterCommands"] = new List<object?>(); map["exitCommands"] = new List<object?>();
                }
                if (allowLegacy && !map.ContainsKey("movement"))
                    map["movement"] = JsonReader.ParseObject(WriteMovement(new ZoneMovement()));
                if (allowLegacy && !map.ContainsKey("schedule"))
                    map["schedule"] = JsonReader.ParseObject(WriteSchedule(new ZoneSchedule()));
                if (allowLegacy && !map.ContainsKey("noCreatureBlockDamage") && !map.ContainsKey("noExplosionBlockDamage"))
                { map["noCreatureBlockDamage"] = false; map["noExplosionBlockDamage"] = false; }
                Keys(map, "id", "name", "type", "enabled", "x1", "z1", "x2", "z2", "noPvp", "noDamage", "blockSpawn", "despawn", "enter", "exit", "bonus", "commandsEnabled", "commandCooldown", "enterCommands", "exitCommands", "movement", "schedule", "noCreatureBlockDamage", "noExplosionBlockDamage");
                var z = new ZoneRule {
                    Id = Text(map, "id", 48), Name = Text(map, "name", 80), Type = Text(map, "type", 24),
                    Enabled = Boolean(map, "enabled"), NoPvp = Boolean(map, "noPvp"), NoDamage = Boolean(map, "noDamage"),
                    NoCreatureBlockDamage = Boolean(map, "noCreatureBlockDamage"), NoExplosionBlockDamage = Boolean(map, "noExplosionBlockDamage"),
                    X1 = Number(map, "x1", -500000, 500000), Z1 = Number(map, "z1", -500000, 500000),
                    X2 = Number(map, "x2", -500000, 500000), Z2 = Number(map, "z2", -500000, 500000),
                    BlockSpawn = (int)Number(map, "blockSpawn", 0, 7, true), Despawn = (int)Number(map, "despawn", 0, 7, true),
                    Enter = Text(map, "enter", 240), Exit = Text(map, "exit", 240), Bonus = Text(map, "bonus", 24),
                    CommandsEnabled = Boolean(map, "commandsEnabled"), CommandCooldown = (int)Number(map, "commandCooldown", 10, 86400, true),
                    EnterCommands = Commands(map, "enterCommands"), ExitCommands = Commands(map, "exitCommands"),
                    Movement = ReadMovement(map["movement"], allowLegacy), Schedule = ReadSchedule(map["schedule"])
                };
                if (!Regex.IsMatch(z.Id, "\\A[a-z0-9][a-z0-9_-]{0,47}\\z") || !ids.Add(z.Id) ||
                    string.IsNullOrWhiteSpace(z.Name) || z.X1 >= z.X2 || z.Z1 >= z.Z2 ||
                    !new[] { "safe", "information", "sanctuary", "bonus", "custom", "restricted", "portal", "prison", "event" }.Contains(z.Type) ||
                    !new[] { "none", "regeneration", "stamina", "speed" }.Contains(z.Bonus)) throw Invalid();
                zones.Add(z);
                if (z.Movement.Mode == "prison" && z.Schedule.Enabled) throw new JsonReader.JsonException("zones_prison_schedule");
            }
            result.Zones = zones.ToArray();
            var prisoners = result.Zones.Where(z => z.Enabled && z.Movement.Mode == "prison")
                .SelectMany(z => z.Movement.Sentences).Select(s => s.Player).ToArray();
            if (prisoners.Distinct(StringComparer.Ordinal).Count() != prisoners.Length) throw Invalid();
            if (result.Zones.Any(z => z.Enabled && z.Movement.Mode != "none" && !ZoneMovementPolicy.DestinationClear(result, z.Movement)))
                throw new JsonReader.JsonException("zones_destination_conflict");
            return result;
        }

        public string[] RefreshSchedules(long utc)
        {
            List<string>? changed = null;
            foreach (var zone in Zones)
            {
                bool active = zone.Schedule.Allows(utc);
                if (zone.ScheduleActive != active) (changed ?? (changed = new List<string>())).Add(zone.Id);
                zone.ScheduleActive = active;
            }
            return changed?.ToArray() ?? Array.Empty<string>();
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

        // Only the target block matters. Explosions are a separate source, even when player-initiated.
        public bool DenyBlockDamage(double x, double z, bool explosion, bool creature)
        {
            if (!explosion && !creature) return false;
            foreach (var zone in Zones)
                if (zone.Contains(x, z) && (explosion ? zone.NoExplosionBlockDamage : zone.NoCreatureBlockDamage)) return true;
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
                Pair("noCreatureBlockDamage", JsonWriter.Bool(z.NoCreatureBlockDamage)), Pair("noExplosionBlockDamage", JsonWriter.Bool(z.NoExplosionBlockDamage)),
                Pair("blockSpawn", JsonWriter.Number(z.BlockSpawn)), Pair("despawn", JsonWriter.Number(z.Despawn)),
                Pair("enter", JsonWriter.String(z.Enter)), Pair("exit", JsonWriter.String(z.Exit)), Pair("bonus", JsonWriter.String(z.Bonus)),
                Pair("commandsEnabled", JsonWriter.Bool(z.CommandsEnabled)), Pair("commandCooldown", JsonWriter.Number(z.CommandCooldown)),
                Pair("enterCommands", JsonWriter.Array(z.EnterCommands.Select(JsonWriter.String))), Pair("exitCommands", JsonWriter.Array(z.ExitCommands.Select(JsonWriter.String))),
                Pair("movement", WriteMovement(z.Movement)), Pair("schedule", WriteSchedule(z.Schedule))
            })))) });

        private static ZoneSchedule ReadSchedule(object? value)
        {
            if (!(value is Dictionary<string, object?> map)) throw Invalid();
            Keys(map, "enabled", "start", "end", "offsetMinutes", "days", "fromMinute", "toMinute");
            var s = new ZoneSchedule {
                Enabled = Boolean(map, "enabled"), Start = (long)Number(map, "start", 0, 253402300799, true),
                End = (long)Number(map, "end", 0, 253402300799, true), OffsetMinutes = (int)Number(map, "offsetMinutes", -720, 840, true),
                Days = (int)Number(map, "days", 0, 127, true), FromMinute = (int)Number(map, "fromMinute", 0, 1439, true),
                ToMinute = (int)Number(map, "toMinute", 0, 1439, true)
            };
            if (s.OffsetMinutes % 15 != 0 || (s.Start != 0 && s.End != 0 && s.Start >= s.End)) throw Invalid();
            return s;
        }
        private static string WriteSchedule(ZoneSchedule s) => JsonWriter.Object(new[] {
            Pair("enabled", JsonWriter.Bool(s.Enabled)), Pair("start", JsonWriter.Number(s.Start)), Pair("end", JsonWriter.Number(s.End)),
            Pair("offsetMinutes", JsonWriter.Number(s.OffsetMinutes)), Pair("days", JsonWriter.Number(s.Days)),
            Pair("fromMinute", JsonWriter.Number(s.FromMinute)), Pair("toMinute", JsonWriter.Number(s.ToMinute))
        });

        private static ZoneMovement ReadMovement(object? value, bool allowLegacy)
        {
            if (!(value is Dictionary<string, object?> map)) throw Invalid();
            if (allowLegacy && !map.ContainsKey("sentences") && !map.ContainsKey("dismount") && !map.ContainsKey("kickOnFailure"))
            { map["sentences"] = new List<object?>(); map["dismount"] = false; map["kickOnFailure"] = false; }
            Keys(map, "mode", "priority", "minLevel", "maxLevel", "players", "x", "y", "z", "cooldown", "message", "sentences", "dismount", "kickOnFailure");
            var m = new ZoneMovement {
                Mode = Text(map, "mode", 16), Priority = (int)Number(map, "priority", -1000, 1000, true),
                MinLevel = (int)Number(map, "minLevel", 0, 10000, true), MaxLevel = (int)Number(map, "maxLevel", 0, 10000, true),
                X = (int)Number(map, "x", -500000, 500000, true), Y = (int)Number(map, "y", 2, 251, true),
                Z = (int)Number(map, "z", -500000, 500000, true), Cooldown = (int)Number(map, "cooldown", 10, 86400, true),
                Message = Text(map, "message", 240), Dismount = Boolean(map, "dismount"), KickOnFailure = Boolean(map, "kickOnFailure")
            };
            if (!new[] { "none", "restricted", "portal", "prison", "event" }.Contains(m.Mode) || (m.MaxLevel != 0 && m.MinLevel > m.MaxLevel) ||
                !(map["players"] is List<object?> list) || list.Count > 64) throw Invalid();
            var ids = new HashSet<string>(StringComparer.Ordinal);
            foreach (var item in list)
                if (!(item is string id) || !Regex.IsMatch(id, "\\A[A-Za-z][A-Za-z0-9]{0,23}_[A-Za-z0-9_-]{1,96}\\z") || !ids.Add(id)) throw Invalid();
            m.Players = ids.OrderBy(id => id, StringComparer.Ordinal).ToArray();
            if (!(map["sentences"] is List<object?> sentences) || sentences.Count > 64) throw Invalid();
            var assignments = new List<ZoneSentence>();
            ids.Clear();
            foreach (var item in sentences)
            {
                if (!(item is Dictionary<string, object?> sentence)) throw Invalid();
                Keys(sentence, "player", "until");
                var id = Text(sentence, "player", 121);
                if (!Regex.IsMatch(id, "\\A[A-Za-z][A-Za-z0-9]{0,23}_[A-Za-z0-9_-]{1,96}\\z") || !ids.Add(id)) throw Invalid();
                assignments.Add(new ZoneSentence { Player = id, Until = (long)Number(sentence, "until", 0, 253402300799, true) });
            }
            m.Sentences = assignments.OrderBy(s => s.Player, StringComparer.Ordinal).ToArray();
            return m;
        }
        private static string WriteMovement(ZoneMovement m) => JsonWriter.Object(new[] {
            Pair("mode", JsonWriter.String(m.Mode)), Pair("priority", JsonWriter.Number(m.Priority)),
            Pair("minLevel", JsonWriter.Number(m.MinLevel)), Pair("maxLevel", JsonWriter.Number(m.MaxLevel)),
            Pair("players", JsonWriter.Array(m.Players.Select(JsonWriter.String))),
            Pair("x", JsonWriter.Number(m.X)), Pair("y", JsonWriter.Number(m.Y)), Pair("z", JsonWriter.Number(m.Z)),
            Pair("cooldown", JsonWriter.Number(m.Cooldown)), Pair("message", JsonWriter.String(m.Message)),
            Pair("dismount", JsonWriter.Bool(m.Dismount)), Pair("kickOnFailure", JsonWriter.Bool(m.KickOnFailure)),
            Pair("sentences", JsonWriter.Array(m.Sentences.Select(s => JsonWriter.Object(new[] {
                Pair("player", JsonWriter.String(s.Player)), Pair("until", JsonWriter.Number(s.Until)) }))))
        });

        private static KeyValuePair<string, string> Pair(string key, string value) => new KeyValuePair<string, string>(key, value);
        private static JsonReader.JsonException Invalid() => new JsonReader.JsonException("invalid_zones");
        private static string[] Commands(Dictionary<string, object?> map, string key)
        {
            if (!(map[key] is List<object?> list) || list.Count > 4) throw Invalid();
            var result = new List<string>();
            foreach (var value in list)
            {
                if (!(value is string command)) throw Invalid();
                try { ZoneCommands.Parse(command); } catch (ArgumentException) { throw Invalid(); }
                result.Add(command);
            }
            if (result.Count(c => c.StartsWith("teleportplayer ", StringComparison.Ordinal)) > 1) throw Invalid();
            return result.ToArray();
        }
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
