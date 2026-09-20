using System;
using System.Text.RegularExpressions;
using Aurum.Companion.Core.Json;

namespace Aurum.Companion.Core.Game
{
    public sealed class SavedStackChange
    {
        public string PlayerId = "", Revision = "", Section = "", RequestId = "";
        public int Slot, Count;
        public static SavedStackChange Read(string playerId, string body)
        {
            var map = JsonReader.ParseObject(body);
            string Text(string key) => JsonReader.StringOrNull(map, key) ?? "";
            int Number(string key, int max)
            {
                if (!map.TryGetValue(key, out var v) || !(v is double n) || double.IsNaN(n) || n < 0 || n > max || n != Math.Truncate(n))
                    throw new JsonReader.JsonException("invalid_stack");
                return (int)n;
            }
            var c = new SavedStackChange { PlayerId = playerId, Revision = Text("revision"), Section = Text("section"), RequestId = Text("requestId"), Slot = Number("slot", 255), Count = Number("count", int.MaxValue) };
            if (!InventoryRequest.ValidPlayerId(playerId) || !JsonReader.BoolOrDefault(map, "confirmed") ||
                !Regex.IsMatch(c.Revision, "\\A[a-f0-9]{64}\\z") || !Guid.TryParseExact(c.RequestId, "D", out _) ||
                (c.Section != "belt" && c.Section != "bag") || (c.Section == "belt" && c.Slot >= 32))
                throw new JsonReader.JsonException("invalid_stack");
            c.RequestId = c.RequestId.ToLowerInvariant(); return c;
        }
    }
}
