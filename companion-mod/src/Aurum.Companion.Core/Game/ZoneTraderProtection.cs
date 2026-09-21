using System;
using System.Linq;
using Aurum.Companion.Core.Json;

namespace Aurum.Companion.Core.Game
{
    // Immutable startup snapshot. Live rule edits never move protection beneath connected clients.
    public sealed class ZoneTraderProtection
    {
        public readonly string Id;
        public readonly int X1, Z1, X2, Z2;
        private ZoneTraderProtection(ZoneRule zone)
        {
            Id = zone.Id; X1 = (int)zone.X1; Z1 = (int)zone.Z1; X2 = (int)zone.X2; Z2 = (int)zone.Z2;
        }

        public static void Validate(ZoneRule zone)
        {
            if (!zone.TraderProtection) return;
            if (zone.Schedule.Enabled) throw new JsonReader.JsonException("zones_protect_schedule");
            if (new[] { zone.X1, zone.Z1, zone.X2, zone.Z2 }.Any(n => double.IsNaN(n) || double.IsInfinity(n) || n != Math.Truncate(n) || Math.Abs(n) > 500000) ||
                zone.X2 <= zone.X1 || zone.Z2 <= zone.Z1 || zone.X2 - zone.X1 > 32760 || zone.Z2 - zone.Z1 > 32760)
                throw new JsonReader.JsonException("zones_protect_bounds");
        }

        public static ZoneTraderProtection[] Capture(ZoneRules rules) => rules.Zones
            .Where(z => z.Enabled && z.TraderProtection).OrderBy(z => z.Id, StringComparer.Ordinal)
            .Select(z => { Validate(z); return new ZoneTraderProtection(z); }).ToArray();

        public static bool Pending(ZoneTraderProtection[] applied, ZoneRules rules)
        {
            var desired = Capture(rules);
            return applied.Length != desired.Length || applied.Where((a, i) =>
                a.Id != desired[i].Id || a.X1 != desired[i].X1 || a.X2 != desired[i].X2 ||
                a.Z1 != desired[i].Z1 || a.Z2 != desired[i].Z2).Any();
        }
    }
}
