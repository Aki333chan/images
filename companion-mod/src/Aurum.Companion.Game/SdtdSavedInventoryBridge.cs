using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Threading;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Json;

namespace Aurum.Companion.Game
{
    internal sealed partial class SdtdGameBridge : ISavedInventoryBridge
    {
        private int _savedRead;
        // Load() silently falls back to .bak. Decode our checked primary-file bytes instead.
        // Private native schema reader: fail closed if the exact signature changes.
        private static readonly MethodInfo? SavedReader = typeof(PlayerDataFile).GetMethod("Read",
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new[] { typeof(PooledBinaryReader), typeof(uint) }, null);

        public string SavedPlayers(string query, int offset) => MainThread.Get(() =>
        {
            var records = GameManager.Instance?.persistentPlayers?.Players;
            if (records == null) return "{\"ready\":false,\"players\":[],\"hasMore\":false,\"truncated\":false}";
            var matches = new List<KeyValuePair<string, string>>();
            int scanned = 0; bool truncated = false;
            foreach (var entry in records)
            {
                if (++scanned > 10000) { truncated = true; break; }
                string id = entry.Value.PrimaryId?.CombinedString ?? "";
                string name = Short(entry.Value.PlayerName?.DisplayName ?? id);
                if (InventoryRequest.ValidPlayerId(id) && (id.IndexOf(query, StringComparison.OrdinalIgnoreCase) >= 0 ||
                    name.IndexOf(query, StringComparison.OrdinalIgnoreCase) >= 0)) matches.Add(new KeyValuePair<string, string>(id, name));
            }
            matches.Sort((a,b) => StringComparer.Ordinal.Compare(a.Key, b.Key));
            var rows = new List<string>();
            for (int i = offset; i < Math.Min(matches.Count, offset + 50); i++)
                rows.Add(JsonWriter.Object(new[] { F("id", JsonWriter.String(matches[i].Key)), F("name", JsonWriter.String(matches[i].Value)) }));
            return JsonWriter.Object(new[] { F("ready", "true"), F("players", JsonWriter.Array(rows)),
                F("hasMore", JsonWriter.Bool(matches.Count > offset + 50)), F("truncated", JsonWriter.Bool(truncated)) });
        });

        public string ReadSavedInventory(string playerId)
        {
            if (!InventoryRequest.ValidPlayerId(playerId)) return SaveError("save_invalid");
            if (Interlocked.CompareExchange(ref _savedRead, 1, 0) != 0) return SaveError("save_busy");
            try
            {
                // Resolve a canonical ID only from the world's registered players, never an arbitrary file name.
                World? capturedWorld = null;
                string? root = MainThread.Get(() =>
                {
                    capturedWorld = GameManager.Instance?.World;
                    if (capturedWorld == null || FindClient(playerId, false) != null) return null;
                    var records = GameManager.Instance?.persistentPlayers?.Players;
                    if (records != null) foreach (var entry in records)
                        if (entry.Value.PrimaryId?.CombinedString == playerId)
                            return GameIO.GetPlayerDataDir();
                    return null;
                });
                if (root == null) return SaveError("save_unavailable");
                var snapshot = SavedPlayerFile.Read(root, playerId); // Disk I/O stays off the game thread.
                string result = MainThread.Get(() =>
                {
                    if (!ReferenceEquals(capturedWorld, GameManager.Instance?.World) || FindClient(playerId, false) != null)
                        return SaveError("save_busy");
                    if (SavedReader == null) return SaveError("save_invalid");
                    using (var stream = new MemoryStream(snapshot.Bytes, false))
                    using (var reader = MemoryPools.poolBinaryReader.AllocSync(false))
                    {
                        reader.SetBaseStream(stream);
                        stream.Position = 5;
                        var data = new PlayerDataFile();
                        SavedReader.Invoke(data, new object[] { reader, (uint)59 });
                        return InventoryJson(data, "saved_file", snapshot.SavedAt.ToString("O"));
                    }
                });
                return snapshot.Unchanged() ? result : SaveError("save_busy");
            }
            catch (FileNotFoundException) { return SaveError("save_missing"); }
            catch (InvalidDataException) { return SaveError("save_invalid"); }
            catch (IOException) { return SaveError("save_busy"); }
            catch (UnauthorizedAccessException) { return SaveError("save_unavailable"); }
            catch (TargetInvocationException) { return SaveError("save_invalid"); }
            finally { Interlocked.Exchange(ref _savedRead, 0); }
        }
        private static string SaveError(string reason) => JsonWriter.Object(new[] {
            F("available", "false"), F("reason", JsonWriter.String(reason)), F("source", JsonWriter.String("saved_file")) });
    }
}
