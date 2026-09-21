using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Json;
using PrefabVolumes;

namespace Aurum.Companion.Game
{
    // Register native geometry once, before world-info packets. No NPC, prefab or world-block writes.
    internal sealed class ZoneTraderRuntime
    {
        private ZoneTraderProtection[] _applied = Array.Empty<ZoneTraderProtection>();
        private readonly List<TraderArea> _areas = new List<TraderArea>();
        private bool _started;
        private string _error = "zones_protect_starting";
        private static readonly FieldInfo PacketAreas = typeof(NetPackageWorldAreas).GetField("traders", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)
            ?? throw new MissingFieldException("NetPackageWorldAreas.traders");

        internal bool Owns(TraderArea area) => _areas.Contains(area);

        internal void FilterForAdmin(ClientInfo client, NetPackageWorldAreas packet)
        {
            // Native admin authority, not panel role/name/client-supplied flags. Applied on login;
            // permission changes require reconnect. Never remove the game's real trader areas.
            var users = GameManager.Instance?.adminTools?.Users;
            if (_areas.Count == 0 || client.PlatformId == null || users == null || users.GetUserPermissionLevel(client) > 0) return;
            var original = (List<TraderArea>)PacketAreas.GetValue(packet);
            packet.Setup(ForAdmin(original));
        }

        internal List<TraderArea> ForAdmin(List<TraderArea> original) => original.Where(a => !Owns(a)).ToList();

        internal void Validate(World world, ZoneRules rules)
        {
            var desired = ZoneTraderProtection.Capture(rules);
            if (desired.Length == 0) return;
            if (World.SandboxUseTraderArea != TraderAreaStates.Default)
                throw new InvalidOperationException("zones_protect_sandbox");
            var existing = world.TraderAreas ?? throw new InvalidOperationException("world_loading");
            // Reject overlap rather than changing which real trader wins the native lookup.
            foreach (var zone in desired)
                foreach (var area in existing)
                    if (!Owns(area) && zone.X2 >= area.AreaBounds.xMin && zone.X1 <= area.AreaBounds.xMax &&
                        zone.Z2 >= area.AreaBounds.zMin && zone.Z1 <= area.AreaBounds.zMax)
                        throw new InvalidOperationException("zones_protect_trader_overlap");
            if (existing.Count + desired.Length > short.MaxValue)
                throw new InvalidOperationException("zones_protect_capacity");
        }

        internal void Start(World world, ZoneRules rules)
        {
            if (_started) return;
            _started = true;
            try
            {
                if (world.IsRemote()) throw new InvalidOperationException("zones_protect_server_only");
                Validate(world, rules);
                var decorator = world.ChunkCache.ChunkProvider.GetDynamicPrefabDecorator()
                    ?? throw new InvalidOperationException("world_loading");
                var snapshot = ZoneTraderProtection.Capture(rules);
                // Native constructor adds two X/Z padding blocks; -2 cancels them exactly.
                // Both the server and serialized client use inclusive X/Z bounds, full height.
                var additions = snapshot.Select(z => new TraderArea(
                    new Vector3i(z.X1, 0, z.Z1), new Vector3i(z.X2 - z.X1, 256, z.Z2 - z.Z1),
                    new Vector3i(-2, 0, -2), new PrefabTeleportVolumeList(null))).ToArray();
                foreach (var area in additions) { decorator.AddTrader(area); _areas.Add(area); }
                _applied = snapshot;
                _error = "";
                Log.Out("[AurumCompanion] Startup Protect areas registered: " + snapshot.Length + ". Gameplay verification required.");
            }
            catch (Exception e)
            {
                _error = e.Message.StartsWith("zones_protect_", StringComparison.Ordinal) ? e.Message : "zones_protect_unavailable";
                Log.Error("[AurumCompanion] Startup Protect not ready: " + e.Message);
            }
        }

        internal string Status(ZoneRules rules) => JsonWriter.Object(new[] {
            new KeyValuePair<string, string>("pending", JsonWriter.Bool(ZoneTraderProtection.Pending(_applied, rules))),
            new KeyValuePair<string, string>("applied", JsonWriter.Number(_applied.Length)),
            new KeyValuePair<string, string>("error", JsonWriter.String(_applied.Length > 0 && World.SandboxUseTraderArea != TraderAreaStates.Default ? "zones_protect_sandbox" : _error))
        });
    }
}
