using System;
using System.Collections.Generic;

namespace Aurum.Companion.Core.Game
{
    public struct ZoneBonuses
    {
        public double Regeneration, Stamina, Speed;

        // Caller supplies zones containing the player. Different effects coexist; equal effects never stack.
        public static ZoneBonuses Strongest(IEnumerable<ZoneRule> zones)
        {
            var result = new ZoneBonuses();
            foreach (var zone in zones)
            {
                if (!zone.IsActive) continue;
                result.Regeneration = Math.Max(result.Regeneration, zone.Bonuses.Regeneration);
                result.Stamina = Math.Max(result.Stamina, zone.Bonuses.Stamina);
                result.Speed = Math.Max(result.Speed, zone.Bonuses.Speed);
            }
            return result;
        }

        // Native RunSpeed perc_add uses a fraction; the editor uses percent.
        public float NativeValue(int index) => (float)(index == 1 ? Regeneration : index == 2 ? Stamina : index == 3 ? Speed / 100 : 0);
    }
}
