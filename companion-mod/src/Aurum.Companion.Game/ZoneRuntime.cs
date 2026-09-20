using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using Aurum.Companion.Core.Game;
using HarmonyLib;
using UnityEngine;

namespace Aurum.Companion.Game
{
    // One instance on the game thread. No network requests or background world access.
    internal sealed class ZoneRuntime : IDisposable
    {
        internal static ZoneRuntime? Current;
        private readonly SdtdGameBridge _bridge;
        private readonly Harmony _harmony = new Harmony("ovh.aurumgg.companion.zones");
        private readonly List<MethodBase> _patched = new List<MethodBase>();
        private ZoneRules _rules = new ZoneRules();
        private string? _path;
        private string? _error;
        private float _nextTick;
        private int _creatureCursor;
        private readonly Dictionary<int, HashSet<string>> _inside = new Dictionary<int, HashSet<string>>();
        private readonly Dictionary<int, float> _noticeAfter = new Dictionary<int, float>();
        private static readonly string[] Buffs = { "aurumZoneProtection", "aurumZoneRegeneration", "aurumZoneStamina", "aurumZoneSpeed" };

        public ZoneRuntime(SdtdGameBridge bridge)
        {
            _bridge = bridge;
            try
            {
                Patch(typeof(EntityAlive), nameof(EntityAlive.DamageEntity), nameof(BeforeDamage), false,
                    typeof(DamageSource), typeof(int), typeof(bool), typeof(float));
                Patch(typeof(NetPackageDamageEntity), nameof(NetPackageDamageEntity.ProcessPackage), nameof(BeforePacket), false,
                    typeof(World), typeof(GameManager));
                Patch(typeof(World), nameof(World.SpawnEntityInWorld), nameof(AfterSpawn), true, typeof(Entity));
                Current = this;
                ModEvents.GameUpdate.RegisterHandler(Update);
            }
            catch { Unpatch(); throw; }
        }

        private void Patch(Type type, string name, string hook, bool postfix, params Type[] parameters)
        {
            var method = type.GetMethod(name, parameters) ?? throw new MissingMethodException(type.FullName, name);
            var handler = new HarmonyMethod(typeof(ZoneRuntime).GetMethod(hook, BindingFlags.Static | BindingFlags.NonPublic));
            _patched.Add(method);
            _harmony.Patch(method, prefix: postfix ? null : handler, postfix: postfix ? handler : null);
        }

        private void EnsureWorld()
        {
            if (GameManager.Instance?.World == null) throw new InvalidOperationException("world_loading");
            string path = Path.Combine(GameIO.GetSaveGameDir(), "aurum-zones.json");
            if (_path == path) return;
            _path = path; _rules = new ZoneRules(); _inside.Clear(); _noticeAfter.Clear(); _error = null;
            try
            {
                if (File.Exists(path))
                {
                    if (new FileInfo(path).Length > 131072) throw new InvalidDataException("zones_file_too_large");
                    var loaded = ZoneRules.Read(File.ReadAllText(path));
                    CheckBuffs(loaded);
                    _rules = loaded;
                }
            }
            catch (Exception e)
            {
                _error = "zones_configuration_invalid";
                Log.Error("[AurumCompanion] Zones inactive; configuration was not changed: " + e.Message);
            }
        }

        public string Read()
        {
            EnsureWorld();
            if (_error != null) throw new InvalidOperationException(_error);
            return _rules.Write();
        }

        public string Save(ZoneRules next)
        {
            EnsureWorld();
            if (_error != null) throw new InvalidOperationException(_error);
            if (next.Revision != _rules.Revision || next.WorldId != _rules.WorldId) throw new InvalidOperationException("zones_revision_conflict");
            CheckBuffs(next);
            next.Revision++;
            string temporary = _path + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try
            {
                // At most 128 KiB, manual saves only; durable replacement precedes activation.
                using (var file = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                using (var writer = new StreamWriter(file, new System.Text.UTF8Encoding(false)))
                { writer.Write(next.Write()); writer.Flush(); file.Flush(true); }
                if (File.Exists(_path)) File.Replace(temporary, _path!, _path + ".previous");
                else File.Move(temporary, _path!);
            }
            finally { if (File.Exists(temporary)) File.Delete(temporary); }
            _rules = next;
            _inside.Clear(); // Edits must not replay entry/exit messages or future rewards.
            _nextTick = 0;
            return _rules.Write();
        }

        private static void CheckBuffs(ZoneRules rules)
        {
            foreach (var z in rules.Zones)
            {
                if (!z.Enabled) continue;
                if (z.NoDamage && BuffManager.GetBuff(Buffs[0]) == null) throw new InvalidOperationException("zones_buffs_missing");
                int bonus = BonusIndex(z.Bonus);
                if (bonus > 0 && BuffManager.GetBuff(Buffs[bonus]) == null) throw new InvalidOperationException("zones_buffs_missing");
            }
        }

        private void Update(ref ModEvents.SGameUpdateData data)
        {
            if (Time.realtimeSinceStartup < _nextTick || GameManager.Instance?.World == null) return;
            _nextTick = Time.realtimeSinceStartup + 1f;
            try
            {
                EnsureWorld();
                if (_error != null) return;
                var world = GameManager.Instance.World;
                var online = new HashSet<int>();
                foreach (var player in world.Players.list)
                {
                    if (player == null || player.IsDead()) continue;
                    online.Add(player.entityId);
                    var active = _rules.Zones.Where(z => z.Contains(player.position.x, player.position.z)).ToArray();
                    var ids = new HashSet<string>(active.Select(z => z.Id), StringComparer.Ordinal);
                    if (_inside.TryGetValue(player.entityId, out var before) &&
                        (!_noticeAfter.TryGetValue(player.entityId, out float after) || Time.realtimeSinceStartup >= after))
                    {
                        var notices = new List<string>();
                        foreach (var z in active) if (!before.Contains(z.Id) && z.Enter.Length > 0) notices.Add(z.Enter.Replace("{zone}", z.Name));
                        foreach (var z in _rules.Zones) if (before.Contains(z.Id) && !ids.Contains(z.Id) && z.Exit.Length > 0) notices.Add(z.Exit.Replace("{zone}", z.Name));
                        var client = ConnectionManager.Instance.Clients.ForEntityId(player.entityId);
                        if (client?.InternalId != null)
                            foreach (string message in notices.Take(3)) _bridge.SendPrivateMessage(client.InternalId.CombinedString, message);
                        if (notices.Count > 0) _noticeAfter[player.entityId] = Time.realtimeSinceStartup + 3f;
                    }
                    _inside[player.entityId] = ids;
                    for (int i = 0; i < Buffs.Length; i++)
                    {
                        bool wanted = i == 0 ? active.Any(z => z.NoDamage) : active.Any(z => BonusIndex(z.Bonus) == i);
                        if (wanted) player.Buffs.AddBuff(Buffs[i]); // Short lease; expires if mod stops/fails.
                        else if (player.Buffs.HasBuff(Buffs[i])) player.Buffs.RemoveBuff(Buffs[i]);
                    }
                }
                foreach (int id in _inside.Keys.Where(id => !online.Contains(id)).ToArray()) { _inside.Remove(id); _noticeAfter.Remove(id); }
                // ponytail: bounded scan of loaded entities only (64/sec). Use a native movement hook if a measured need arises.
                if (_rules.Zones.Any(z => z.Enabled && z.Despawn != 0))
                {
                    var entities = world.Entities.list;
                    int count = Math.Min(64, entities.Count);
                    var remove = new List<int>();
                    for (int i = 0; i < count; i++)
                    {
                        if (_creatureCursor >= entities.Count) _creatureCursor = 0;
                        var e = entities[_creatureCursor++];
                        if (e != null && _rules.DenyCreature(e.position.x, e.position.z, Category(e), true)) remove.Add(e.entityId);
                    }
                    foreach (int id in remove) world.RemoveEntity(id, EnumRemoveEntityReason.Despawned);
                }
            }
            catch (Exception e)
            {
                _error = "zones_runtime_failed";
                Log.Error("[AurumCompanion] Zones runtime failed; effect leases expire: " + e);
            }
        }

        private static int BonusIndex(string bonus) => bonus == "regeneration" ? 1 : bonus == "stamina" ? 2 : bonus == "speed" ? 3 : 0;
        private static int Category(Entity e)
        {
            if (!(e is EntityAlive alive) || e is EntityPlayer || e is EntityTrader || !e.IsAlive()) return 0;
            if ((alive.entityFlags & EntityFlags.Animal) != 0) return alive.EntityClass.bIsEnemyEntity ? 4 : 2;
            return (alive.entityFlags & EntityFlags.Zombie) != 0 ? 1 : 0;
        }
        private bool Deny(EntityAlive victim, DamageSource source)
        {
            if (_error != null || !(victim is EntityPlayer) || victim.world.IsRemote()) return false;
            var attacker = victim.world.GetEntity(source.getEntityId());
            bool pvp = attacker is EntityPlayer && attacker.entityId != victim.entityId;
            return _rules.DenyDamage(victim.position.x, victim.position.z, pvp, attacker?.position.x ?? 0, attacker?.position.z ?? 0);
        }
        private static bool BeforeDamage(EntityAlive __instance, DamageSource _damageSource, ref int __result)
        {
            if (Current?.Deny(__instance, _damageSource) != true) return true;
            __result = -1; return false;
        }
        private static bool BeforePacket(NetPackageDamageEntity __instance, World _world)
        {
            var current = Current;
            if (current == null || current._error != null || _world == null || _world.IsRemote()) return true;
            var victim = _world.GetEntity(__instance.entityId) as EntityPlayer;
            if (victim == null) return true;
            var attacker = _world.GetEntity(__instance.attackerEntityId);
            bool pvp = attacker is EntityPlayer && attacker.entityId != victim.entityId;
            return !current._rules.DenyDamage(victim.position.x, victim.position.z, pvp, attacker?.position.x ?? 0, attacker?.position.z ?? 0);
        }
        private static void AfterSpawn(World __instance, Entity _entity)
        {
            var current = Current;
            if (current == null || current._error != null || _entity == null || __instance.IsRemote()) return;
            if (current._rules.DenyCreature(_entity.position.x, _entity.position.z, Category(_entity), false))
                __instance.RemoveEntity(_entity.entityId, EnumRemoveEntityReason.Despawned);
        }
        public void Dispose()
        {
            ModEvents.GameUpdate.UnregisterHandler(Update);
            if (Current == this) Current = null;
            Unpatch();
            _inside.Clear(); _noticeAfter.Clear();
        }
        private void Unpatch()
        {
            foreach (var method in _patched) _harmony.Unpatch(method, HarmonyPatchType.All, _harmony.Id);
            _patched.Clear();
        }
    }
}
