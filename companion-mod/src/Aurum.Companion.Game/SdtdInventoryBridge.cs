using System;
using System.Collections.Generic;
using Aurum.Companion.Core.Json;

namespace Aurum.Companion.Game
{
    internal sealed partial class SdtdGameBridge
    {
        public string ReadInventory(string playerId) => MainThread.Get(() =>
        {
            ClientInfo? client = null;
            foreach (var candidate in ConnectionManager.Instance.Clients.List)
            {
                if (candidate != null && (candidate.InternalId?.CombinedString == playerId ||
                    candidate.PlatformId?.CombinedString == playerId || candidate.CrossplatformId?.CombinedString == playerId))
                { client = candidate; break; }
            }
            if (client == null || client.disconnecting) return "{\"available\":false,\"reason\":\"offline\"}";
            // Last client-supplied snapshot, NOT a live authoritative inventory.
            // No forced save, disk reads, network requests or background scans.
            var data = client.latestPlayerData;
            if (data == null) return "{\"available\":false,\"reason\":\"snapshot_pending\"}";
            var items = new List<string>();
            bool truncated = false;
            void Add(string section, int slot, ItemValue? value, int count)
            {
                if (value == null || value.IsEmpty() || count <= 0) return;
                string name = value.ItemClass?.GetItemName() ?? ("item_" + value.type);
                if (name.Length > 128) name = name.Substring(0, 128);
                items.Add(JsonWriter.Object(new[] {
                    F("section", JsonWriter.String(section)), F("slot", JsonWriter.Number(slot)),
                    F("itemId", JsonWriter.Number(value.type)), F("name", JsonWriter.String(name)),
                    F("count", JsonWriter.Number(count)), F("quality", JsonWriter.Number(value.Quality))
                }));
            }
            void Stacks(string section, ItemStack[]? stacks, int limit)
            {
                if (stacks == null) return;
                if (stacks.Length > limit) truncated = true;
                for (int i = 0; i < Math.Min(stacks.Length, limit); i++)
                    if (stacks[i] != null) Add(section, i, stacks[i].itemValue, stacks[i].count);
            }
            Stacks("belt", data.inventory, 32);
            Stacks("bag", data.bag?.GetSlots(), 256);
            var equipment = data.equipment?.GetItems();
            if (equipment != null)
            {
                if (equipment.Length > 32) truncated = true;
                for (int i = 0; i < Math.Min(equipment.Length, 32); i++) Add("equipment", i, equipment[i], 1);
            }
            if (data.dragAndDropItem != null) Add("cursor", 0, data.dragAndDropItem.itemValue, data.dragAndDropItem.count);
            return JsonWriter.Object(new[] {
                F("available", "true"), F("source", JsonWriter.String("client_snapshot")),
                F("items", JsonWriter.Array(items)), F("truncated", JsonWriter.Bool(truncated))
            });
        });
    }
}
