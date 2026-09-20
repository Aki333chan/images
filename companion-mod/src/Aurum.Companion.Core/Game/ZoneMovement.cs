using System;
using System.Collections.Generic;
using System.Linq;

namespace Aurum.Companion.Core.Game
{
    public sealed class ZoneMovement
    {
        public string Mode = "none", Message = "";
        public int Priority, MinLevel, MaxLevel, X, Y = 65, Z, Cooldown = 10;
        public string[] Players = Array.Empty<string>();
        public ZoneSentence[] Sentences = Array.Empty<ZoneSentence>();
        public bool Dismount, KickOnFailure;
        public bool IsContainment => Mode == "prison" || Mode == "event";
        // Empty list means everyone; list and level requirements combine with AND.
        public bool Allows(int level, IEnumerable<string> identities) =>
            level >= MinLevel && (MaxLevel == 0 || level <= MaxLevel) &&
            (Players.Length == 0 || identities.Any(id => Players.Contains(id, StringComparer.Ordinal)));
    }

    public sealed class ZoneSentence
    {
        public string Player = "";
        // Absolute UTC Unix seconds; zero means manual release. Offline time counts.
        public long Until;
    }

    // Online event membership only. Prison assignments themselves are durable zone configuration.
    public sealed class ZoneContainment
    {
        private readonly Dictionary<int, HashSet<string>> _events = new Dictionary<int, HashSet<string>>();
        public ZoneRule? Select(ZoneRules rules, int player, string[] identities, double x, double z,
            ISet<string>? before, long utc)
        {
            var prison = ZoneMovementPolicy.Order(rules.Zones.Where(v => v.Enabled && v.Movement.Mode == "prison" &&
                v.Movement.Sentences.Any(s => (s.Until == 0 || s.Until > utc) && identities.Contains(s.Player, StringComparer.Ordinal)))).FirstOrDefault();
            if (prison != null) { Remove(player); return prison; }
            if (!_events.TryGetValue(player, out var joined))
                _events[player] = joined = new HashSet<string>(StringComparer.Ordinal);
            var eligible = rules.Zones.Where(v => v.Enabled && v.Movement.Mode == "event" &&
                v.Movement.Players.Any(id => identities.Contains(id, StringComparer.Ordinal))).ToArray();
            joined.RemoveWhere(id => !eligible.Any(v => v.Id == id));
            if (before != null)
                foreach (var zone in eligible)
                    if (!before.Contains(zone.Id) && zone.Contains(x, z)) joined.Add(zone.Id);
            return ZoneMovementPolicy.Order(eligible.Where(v => joined.Contains(v.Id))).FirstOrDefault();
        }
        public void RulesChanged(ZoneRules rules)
        {
            var enabled = new HashSet<string>(rules.Zones.Where(v => v.Enabled && v.Movement.Mode == "event").Select(v => v.Id), StringComparer.Ordinal);
            foreach (var joined in _events.Values) joined.RemoveWhere(id => !enabled.Contains(id));
        }
        public void Remove(int player) => _events.Remove(player);
        public void Retain(ISet<int> online)
        {
            foreach (int player in _events.Keys.Where(id => !online.Contains(id)).ToArray()) _events.Remove(player);
        }
        public void Clear() => _events.Clear();
    }

    public static class ZoneMovementPolicy
    {
        // Denial beats portals, then larger priority, then stable ID (not array/save order).
        public static ZoneRule? Select(ZoneRules rules, double x, double z, int level,
            string[] identities, ISet<string>? before)
        {
            var active = rules.Zones.Where(v => v.Contains(x, z));
            var denied = active.Where(v => v.Movement.Mode == "restricted" && !v.Movement.Allows(level, identities));
            var portals = active.Where(v => v.Movement.Mode == "portal" && before != null &&
                !before.Contains(v.Id) && v.Movement.Allows(level, identities));
            return Order(denied).FirstOrDefault() ?? Order(portals).FirstOrDefault();
        }
        internal static IOrderedEnumerable<ZoneRule> Order(IEnumerable<ZoneRule> zones) =>
            zones.OrderByDescending(v => v.Movement.Priority).ThenBy(v => v.Id, StringComparer.Ordinal);

        // Deliberately no portal chains or destinations inside restricted areas, even for allowed users.
        public static bool DestinationClear(ZoneRules rules, ZoneMovement movement)
        {
            double x = movement.X + 0.5, z = movement.Z + 0.5;
            var owner = rules.Zones.FirstOrDefault(v => ReferenceEquals(v.Movement, movement));
            if (movement.IsContainment && (owner == null || !owner.Contains(x, z))) return false;
            return !rules.Zones.Any(v => v.Movement.Mode != "none" && v.Contains(x, z) &&
                !(movement.IsContainment && ReferenceEquals(v, owner)));
        }
    }
}
