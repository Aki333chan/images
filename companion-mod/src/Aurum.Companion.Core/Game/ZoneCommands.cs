using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text.RegularExpressions;

namespace Aurum.Companion.Core.Game
{
    // No shell or free-form console: every argument is validated before native dispatch.
    public static class ZoneCommands
    {
        public static string[] Parse(string command)
        {
            if (command == null || command.Length > 160 || command.Any(char.IsControl)) throw new ArgumentException("invalid_zone_command");
            var p = command.Split(' ');
            if (p.Length < 3 || p[1] != "{player}") throw new ArgumentException("invalid_zone_command");
            if ((p[0] == "buffplayer" || p[0] == "debuffplayer") && p.Length == 3 &&
                Regex.IsMatch(p[2], "\\A[A-Za-z][A-Za-z0-9_]{0,79}\\z") && !p[2].StartsWith("aurumZone", StringComparison.OrdinalIgnoreCase)) return p;
            if (p[0] == "teleportplayer" && p.Length == 5 &&
                Coordinate(p[2], -500000, 500000) && Coordinate(p[3], -1, 2048) && Coordinate(p[4], -500000, 500000)) return p;
            throw new ArgumentException("invalid_zone_command");
        }
        private static bool Coordinate(string s, int min, int max) =>
            Regex.IsMatch(s, "\\A-?[0-9]{1,6}\\z") && int.TryParse(s, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out int n) && n >= min && n <= max;
    }

    // Game-thread only. Stable player IDs retain cooldown across reconnects, bounded TTL storage.
    public sealed class ZoneCommandGate
    {
        private readonly Dictionary<string, double> _after = new Dictionary<string, double>(StringComparer.Ordinal);
        private double _sweepAfter;
        public bool TryBegin(string player, string zone, bool entering, int cooldown, bool teleport, double now)
        {
            if (now >= _sweepAfter)
            {
                foreach (string expiredKey in _after.Where(p => p.Value <= now).Select(p => p.Key).ToArray()) _after.Remove(expiredKey);
                _sweepAfter = now + 60;
            }
            string key = player + "/" + zone + (entering ? "/enter" : "/exit");
            string teleKey = player + "/teleport";
            if ((_after.TryGetValue(key, out double due) && now < due) ||
                (teleport && _after.TryGetValue(teleKey, out due) && now < due) || _after.Count >= 20000) return false;
            // Mark BEFORE execution: uncertain/partial commands are never retried automatically.
            _after[key] = now + cooldown;
            if (teleport) _after[teleKey] = now + 10;
            return true;
        }
        public void Clear() { _after.Clear(); _sweepAfter = 0; }
    }
}
