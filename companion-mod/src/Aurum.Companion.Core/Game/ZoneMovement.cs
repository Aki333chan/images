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
        // Empty list means everyone; list and level requirements combine with AND.
        public bool Allows(int level, IEnumerable<string> identities) =>
            level >= MinLevel && (MaxLevel == 0 || level <= MaxLevel) &&
            (Players.Length == 0 || identities.Any(id => Players.Contains(id, StringComparer.Ordinal)));
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
        private static IOrderedEnumerable<ZoneRule> Order(IEnumerable<ZoneRule> zones) =>
            zones.OrderByDescending(v => v.Movement.Priority).ThenBy(v => v.Id, StringComparer.Ordinal);

        // Deliberately no portal chains or destinations inside restricted areas, even for allowed users.
        public static bool DestinationClear(ZoneRules rules, ZoneMovement movement) =>
            !rules.Zones.Any(v => v.Movement.Mode != "none" && v.Contains(movement.X + 0.5, movement.Z + 0.5));
    }
}
