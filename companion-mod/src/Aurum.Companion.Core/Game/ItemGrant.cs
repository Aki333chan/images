using System;
using System.Collections.Generic;
using Aurum.Companion.Core.Json;

namespace Aurum.Companion.Core.Game
{
    public sealed class ItemGrant
    {
        public string SessionId = "", RequestId = "", PlayerId = "", ItemName = "";
        public int ItemId, Count, Quality;

        public static ItemGrant Read(string playerId, string body)
        {
            var map = JsonReader.ParseObject(body);
            if (!JsonReader.BoolOrDefault(map, "confirmed")) throw new JsonReader.JsonException("confirmation_required");
            string Text(string key) => JsonReader.StringOrNull(map, key) ?? "";
            int Number(string key, int min, int max)
            {
                if (!map.TryGetValue(key, out var value) || !(value is double n) ||
                    double.IsNaN(n) || n < min || n > max || n != Math.Truncate(n))
                    throw new JsonReader.JsonException("invalid_grant");
                return (int)n;
            }
            var grant = new ItemGrant { PlayerId = playerId, SessionId = Text("sessionId"), RequestId = Text("requestId"),
                ItemName = Text("itemName"), ItemId = Number("itemId", 1, int.MaxValue), Count = Number("count", 1, 1000), Quality = Number("quality", 0, 6) };
            if (!InventoryRequest.ValidPlayerId(playerId) || !Guid.TryParseExact(grant.SessionId, "D", out _) ||
                !Guid.TryParseExact(grant.RequestId, "D", out _) || grant.ItemName.Length == 0 || grant.ItemName.Length > 128)
                throw new JsonReader.JsonException("invalid_grant");
            grant.RequestId = grant.RequestId.ToLowerInvariant();
            return grant;
        }
        public bool Same(ItemGrant other) => PlayerId == other.PlayerId && ItemId == other.ItemId &&
            ItemName == other.ItemName && Count == other.Count && Quality == other.Quality;
    }

    // No durable delivery queue: stale requests cannot run after a mod restart because
    // their session ID changes. Unknown outcomes are retained, never automatically retried.
    public sealed class ItemGrantGate
    {
        public string SessionId { get; } = Guid.NewGuid().ToString("D");
        private readonly Dictionary<string, (ItemGrant request, string result)> _results = new Dictionary<string, (ItemGrant, string)>();
        private DateTime _nextGrant;
        private readonly Func<DateTime> _clock;
        public ItemGrantGate(Func<DateTime>? clock = null) { _clock = clock ?? (() => DateTime.UtcNow); }
        public string Execute(ItemGrant request, Func<string> give)
        {
            lock (_results)
            {
                if (!string.Equals(request.SessionId, SessionId, StringComparison.OrdinalIgnoreCase)) return "session_expired";
                if (_results.TryGetValue(request.RequestId, out var old)) return old.request.Same(request) ? old.result : "request_conflict";
                // ponytail: bounded, non-evicting ledger, 4096 grants per mod session.
                // Use a durable journal if administration ever needs more between restarts.
                if (_results.Count >= 4096) return "capacity";
                if (_clock() < _nextGrant) return "cooldown";
                _nextGrant = _clock().AddSeconds(1);
                _results.Add(request.RequestId, (request, "unknown"));
                try { var result = give(); _results[request.RequestId] = (request, result); return result; }
                catch (GameDispatchException e) when (!e.MayHaveExecuted) { _results[request.RequestId] = (request, "not_ready"); return "not_ready"; }
                // Keep unknown in the ledger if any other error happens after execution starts.
            }
        }
    }
}
