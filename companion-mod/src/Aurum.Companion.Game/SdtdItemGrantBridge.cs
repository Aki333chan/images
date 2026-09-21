using System;
using System.Collections.Generic;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Json;

namespace Aurum.Companion.Game
{
    internal sealed partial class SdtdGameBridge
    {
        private static int GrantLimit(ItemClass item) => item.HasQuality ? 1 : Math.Min(1000, item.MaxCount);
        private static bool Grantable(ItemClass? item) => item != null && item.Id > 0 &&
            item.CreativeMode != EnumCreativeMode.None && item.CreativeMode != EnumCreativeMode.Test && GrantLimit(item) > 0;

        public string SearchItems(string query) => MainThread.Get(() =>
        {
            var items = new List<string>();
            bool truncated = false;
            if (ItemClass.list == null || GameManager.Instance?.World == null)
                return "{\"ready\":false,\"items\":[],\"truncated\":false}";
            // V3.2 items START at 65536, after the blocks. Scan the native table, not
            // a ushort range. Still only 100 matches per explicit, debounced search.
            for (int i = 0; i < ItemClass.list.Length; i++)
            {
                var item = ItemClass.list[i];
                if (!Grantable(item)) continue;
                string name = item.GetItemName();
                if (string.IsNullOrEmpty(name) || name.Length > 128 || name.IndexOf(query, StringComparison.OrdinalIgnoreCase) < 0) continue;
                if (items.Count == 100) { truncated = true; break; }
                items.Add(JsonWriter.Object(new[] { F("itemId", JsonWriter.Number(item.Id)), F("name", JsonWriter.String(name)),
                    F("maxCount", JsonWriter.Number(GrantLimit(item))), F("hasQuality", JsonWriter.Bool(item.HasQuality)) }));
            }
            return JsonWriter.Object(new[] { F("ready", "true"), F("items", JsonWriter.Array(items)), F("truncated", JsonWriter.Bool(truncated)) });
        });

        public string DropItem(ItemGrant request) => MainThread.Get(() =>
        {
            var client = FindClient(request.PlayerId, false);
            if (client == null || client.disconnecting) return "offline";
            var manager = GameManager.Instance;
            var world = manager?.World;
            if (world?.Players?.dict == null || !world.Players.dict.TryGetValue(client.entityId, out var player) || !player.IsAlive()) return "not_ready";
            var item = ItemClass.GetForId(request.ItemId);
            if (item == null || !Grantable(item) || item.GetItemName() != request.ItemName) return "invalid_item";
            if (request.Count < 1 || request.Count > GrantLimit(item)) return "invalid_count";
            if (item.HasQuality ? request.Quality < 1 || request.Quality > 6 : request.Quality != 0) return "invalid_quality";
            // One native stack, no client console command and no inventory overwrite.
            // Native drops can be collected by others and expire; UI confirms this explicitly.
            var value = new ItemValue(request.ItemId, request.Quality, request.Quality, false);
            var pos = player.position;
            pos.y += 0.5f;
            manager!.ItemDropServer(new ItemStack(value, request.Count), pos, UnityEngine.Vector3.zero, client.entityId, 300f);
            Log("Item drop " + request.RequestId + " -> " + request.PlayerId + ": item=" + request.ItemId + " count=" + request.Count + " quality=" + request.Quality);
            return "spawned";
        });
    }
}
