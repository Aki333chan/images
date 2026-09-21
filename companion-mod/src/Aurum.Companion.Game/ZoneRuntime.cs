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
        [ThreadStatic] private static World? _explosionWorld;
        private readonly SdtdGameBridge _bridge;
        private readonly Harmony _harmony = new Harmony("ovh.aurumgg.companion.zones");
        private readonly List<MethodBase> _patched = new List<MethodBase>();
        private ZoneRules _rules = new ZoneRules();
        private string? _path;
        private World? _loadedWorld;
        private string? _error;
        private bool _creatureBlockProtection, _explosionBlockProtection, _earlySpawnProtection, _earlySpawnFailed;
        private float _nextTick;
        private int _creatureCursor;
        private readonly ZoneSpawnChecks _spawnChecks = new ZoneSpawnChecks();
        private float _spawnWarningAfter;
        private readonly ZoneCommandGate _commandGate = new ZoneCommandGate();
        private readonly ZoneContainment _containment = new ZoneContainment();
        private readonly Dictionary<int, ReturnAttempts> _returns = new Dictionary<int, ReturnAttempts>();
        private sealed class ReturnAttempts
        {
            public string Zone = "";
            public double Since;
            public int Count;
        }
        private double _commandWarningAfter;
        private readonly Dictionary<int, HashSet<string>> _inside = new Dictionary<int, HashSet<string>>();
        private readonly Dictionary<int, float> _noticeAfter = new Dictionary<int, float>();
        private static readonly string[] Buffs = { "aurumZoneProtection", "aurumZoneRegenerationV2", "aurumZoneStaminaV2", "aurumZoneSpeedV2" };
        private static readonly string[] LegacyBuffs = { "aurumZoneRegeneration", "aurumZoneStamina", "aurumZoneSpeed" };
        private static readonly string[] StrengthVars = { "", "aurumZoneRegenerationStrength", "aurumZoneStaminaStrength", "aurumZoneSpeedStrength" };

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
                PatchBlockDamageFamily();
                Patch(typeof(World), nameof(World.GetLandProtectionHardnessModifier), nameof(BeforeHardness), false,
                    typeof(Vector3i), typeof(EntityAlive), typeof(PersistentPlayerData));
                var explosion = typeof(Explosion).GetMethod(nameof(Explosion.AttackBlocks), new[] { typeof(int), typeof(ItemValue) })
                    ?? throw new MissingMethodException("Explosion.AttackBlocks");
                _patched.Add(explosion);
                _harmony.Patch(explosion,
                    prefix: new HarmonyMethod(typeof(ZoneRuntime).GetMethod(nameof(BeginExplosion), BindingFlags.Static | BindingFlags.NonPublic)),
                    finalizer: new HarmonyMethod(typeof(ZoneRuntime).GetMethod(nameof(EndExplosion), BindingFlags.Static | BindingFlags.NonPublic)));
                TryPatchBiomeSpawn();
                TryPatchWanderingSpawn();
                Current = this;
                ModEvents.GameUpdate.RegisterHandler(Update);
                ModEvents.GameUpdate.RegisterHandler(CheckSpawnedEntities);
                ModEvents.PlayerSpawnedInWorld.RegisterHandler(PlayerSpawned);
                ModEvents.PlayerDisconnected.RegisterHandler(PlayerLeft);
            }
            catch { Dispose(); throw; }
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
            if (_path == path && _loadedWorld == GameManager.Instance.World) return;
            _loadedWorld = GameManager.Instance.World;
            _spawnChecks.Clear(); _spawnWarningAfter = 0;
            _path = path; _rules = new ZoneRules(); _inside.Clear(); _noticeAfter.Clear(); _commandGate.Clear(); _error = null;
            _creatureBlockProtection = _explosionBlockProtection = _earlySpawnProtection = false;
            _containment.Clear(); _returns.Clear();
            try
            {
                if (File.Exists(path))
                {
                    if (new FileInfo(path).Length > 131072) throw new InvalidDataException("zones_file_too_large");
                    var loaded = ZoneRules.Read(File.ReadAllText(path), allowLegacy: true);
                    CheckBuffs(loaded);
                    _rules = loaded;
                    _rules.RefreshSchedules(DateTimeOffset.UtcNow.ToUnixTimeSeconds());
                    RefreshBlockProtection();
                }
            }
            catch (Exception e)
            {
                _error = "zones_configuration_invalid";
                Log.Error("[AurumCompanion] Zones inactive; configuration was not changed: " + e.Message);
            }
        }

        private void PatchBlockDamageFamily()
        {
            // Patch overrides too: some blocks run destruction side effects before calling base.
            // Reflection is startup-only; no direct references to publicized game members.
            var roots = typeof(Block).GetMethods().Where(m => m.Name == "DamageBlock" || m.Name == "OnBlockDamaged").ToArray();
            if (roots.Length != 2) throw new MissingMethodException("Block damage family changed");
            foreach (var type in typeof(Block).Assembly.GetTypes().Where(t => typeof(Block).IsAssignableFrom(t)))
                foreach (var method in type.GetMethods(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly))
                    if (!method.IsAbstract && roots.Contains(method.GetBaseDefinition()))
                    {
                        _patched.Add(method);
                        _harmony.Patch(method, prefix: new HarmonyMethod(typeof(ZoneRuntime).GetMethod(nameof(BeforeBlockDamage), BindingFlags.Static | BindingFlags.NonPublic)));
                    }
        }

        private static bool BeforeBlockDamage(WorldBase __0, BlockValueRef __1, int __3, int __4, ref int __result)
        {
            var current = Current;
            if (current == null || !current._creatureBlockProtection || current._error != null || __0.IsRemote() ||
                __0 == _explosionWorld || __3 <= 0 || !__1.TryGetBlockPos(out var pos)) return true;
            var attacker = __0.GetEntity(__4);
            if (attacker == null || Category(attacker) == 0 || !current._rules.DenyBlockDamage(pos.x, pos.z, false, true)) return true;
            __result = 0;
            return false;
        }

        // Restrict the hardness override to synchronous native explosion calculation only.
        // Finalizer restores the previous context on exceptions and nested explosions.
        private static void BeginExplosion(World ___world, out World? __state)
        {
            __state = _explosionWorld;
            _explosionWorld = ___world.IsRemote() ? null : ___world;
        }
        private static void EndExplosion(World? __state) => _explosionWorld = __state;
        private static bool BeforeHardness(World __instance, Vector3i blockPos, ref float __result)
        {
            var current = Current;
            if (current == null || !current._explosionBlockProtection || current._error != null || __instance != _explosionWorld ||
                !current._rules.DenyBlockDamage(blockPos.x, blockPos.z, true, false)) return true;
            // Native explosion divides damage by hardness before destruction/drop callbacks.
            __result = float.PositiveInfinity;
            return false;
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
            foreach (var zone in next.Zones)
            {
                var old = _rules.Zones.FirstOrDefault(z => z.Id == zone.Id);
                bool changed = old == null || !old.Enabled || old.Movement.Mode != zone.Movement.Mode ||
                    old.Movement.X != zone.Movement.X || old.Movement.Y != zone.Movement.Y || old.Movement.Z != zone.Movement.Z;
                if (changed && zone.Enabled && zone.Movement.Mode != "none" && !DestinationReady(zone.Movement))
                    throw new InvalidOperationException("zones_destination_unavailable");
            }
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
            _rules.RefreshSchedules(DateTimeOffset.UtcNow.ToUnixTimeSeconds());
            RefreshBlockProtection();
            _containment.RulesChanged(next);
            _returns.Clear();
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
                for (int i = 1; i < Buffs.Length; i++)
                    if (z.Bonuses.NativeValue(i) > 0 && BuffManager.GetBuff(Buffs[i]) == null) throw new InvalidOperationException("zones_buffs_missing");
                if (z.CommandsEnabled)
                    foreach (string command in z.EnterCommands.Concat(z.ExitCommands))
                    {
                        var p = ZoneCommands.Parse(command);
                        if (p[0] != "teleportplayer" && BuffManager.GetBuff(p[2]) == null) throw new InvalidOperationException("zones_command_buff_missing");
                    }
            }
        }

        private void RefreshBlockProtection()
        {
            _creatureBlockProtection = _rules.Zones.Any(z => z.IsActive && z.NoCreatureBlockDamage);
            _explosionBlockProtection = _rules.Zones.Any(z => z.IsActive && z.NoExplosionBlockDamage);
            _earlySpawnProtection = _rules.Zones.Any(z => z.IsActive && z.BlockSpawn != 0);
        }

        private void TryPatchBiomeSpawn()
        {
            MethodInfo? target = null;
            try
            {
                target = ZoneBiomeSpawnPatch.Target ?? throw new MissingMethodException("SpawnManagerBiomes.SpawnUpdate");
                _patched.Add(target);
                _harmony.Patch(target, transpiler: new HarmonyMethod(AccessTools.Method(typeof(ZoneBiomeSpawnPatch), "Transpile")));
                Log.Out("[AurumCompanion] Early biome spawn gate installed.");
            }
            catch (Exception e)
            {
                if (target != null) _harmony.Unpatch(target, HarmonyPatchType.All, _harmony.Id);
                Log.Warning("[AurumCompanion] Early biome spawn gate unavailable; deferred spawn checks remain: " + e.Message);
            }
        }

        private void TryPatchWanderingSpawn()
        {
            MethodInfo? target = null;
            try
            {
                target = ZoneWanderingSpawnPatch.Target ?? throw new MissingMethodException("AIWanderingHordeSpawner.UpdateSpawn");
                _patched.Add(target);
                _harmony.Patch(target, transpiler: new HarmonyMethod(AccessTools.Method(typeof(ZoneWanderingSpawnPatch), "Transpile")));
                Log.Out("[AurumCompanion] Early wandering spawn gate installed; other spawn sources use deferred checks.");
            }
            catch (Exception e)
            {
                if (target != null) _harmony.Unpatch(target, HarmonyPatchType.All, _harmony.Id);
                Log.Warning("[AurumCompanion] Early wandering spawn gate unavailable; deferred spawn checks remain: " + e.Message);
            }
        }

        private static bool DenyEarlySpawn(World world, int entityClass, Vector3 position)
        {
            var current = Current;
            if (current == null || current._earlySpawnFailed || !current._earlySpawnProtection ||
                current._error != null || current._loadedWorld != world || world.IsRemote()) return false;
            try
            {
                if (!EntityClass.list.TryGetValue(entityClass, out var definition)) return false;
                // The factory may randomly downgrade a class above the sandbox tier limit.
                // Never draw that replacement twice or decide using the wrong category.
                if (definition.EntityTier > EntityFactory.MaxEntityTier) return false;
                var type = definition.classname;
                if (type == null || !typeof(EntityAlive).IsAssignableFrom(type) ||
                    typeof(EntityPlayer).IsAssignableFrom(type) || typeof(EntityTrader).IsAssignableFrom(type)) return false;
                int category = (definition.entityFlags & EntityFlags.Animal) != 0 ? (definition.bIsEnemyEntity ? 4 : 2) :
                    (definition.entityFlags & EntityFlags.Zombie) != 0 ? 1 : 0;
                return current._rules.DenyCreature(position.x, position.z, category, false);
            }
            catch (Exception e)
            {
                current._earlySpawnFailed = true;
                Log.Warning("[AurumCompanion] Early spawn checks failed; deferred spawn checks remain: " + e.Message);
                return false;
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
                long utc = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
                var scheduleChanges = _rules.RefreshSchedules(utc);
                if (scheduleChanges.Length != 0) { _containment.RulesChanged(_rules); RefreshBlockProtection(); }
                int commandBudget = 8;
                foreach (var player in world.Players.list)
                {
                    if (player == null) continue;
                    if (player.IsDead()) { _containment.Remove(player.entityId); _returns.Remove(player.entityId); _inside.Remove(player.entityId); continue; }
                    online.Add(player.entityId);
                    var active = _rules.Zones.Where(z => z.Contains(player.position.x, player.position.z)).ToArray();
                    var ids = new HashSet<string>(active.Select(z => z.Id), StringComparer.Ordinal);
                    bool observed = _inside.TryGetValue(player.entityId, out var before);
                    // Clock transitions are not crossings; preserve real crossings in unrelated zones.
                    if (observed)
                        foreach (string changed in scheduleChanges)
                            if (ids.Contains(changed)) before!.Add(changed); else before!.Remove(changed);
                    var clientInfo = ConnectionManager.Instance.Clients.ForEntityId(player.entityId);
                    var identities = new[] { clientInfo?.InternalId?.CombinedString, clientInfo?.PlatformId?.CombinedString,
                        clientInfo?.CrossplatformId?.CombinedString }.Where(id => id != null).Cast<string>().ToArray();
                    var holding = _containment.Select(_rules, player.entityId, identities, player.position.x,
                        player.position.z, before, utc);
                    var movement = holding != null ? (holding.Contains(player.position.x, player.position.z) ? null : holding) :
                        ZoneMovementPolicy.Select(_rules, player.position.x, player.position.z,
                            player.Progression?.Level ?? -1, identities, before);
                    if (movement == null || !movement.Movement.IsContainment) _returns.Remove(player.entityId);
                    if (observed &&
                        (!_noticeAfter.TryGetValue(player.entityId, out float after) || Time.realtimeSinceStartup >= after))
                    {
                        var notices = new List<string>();
                        foreach (var z in active) if (!before!.Contains(z.Id) && z.Enter.Length > 0) notices.Add(z.Enter.Replace("{zone}", z.Name));
                        foreach (var z in _rules.Zones) if (before!.Contains(z.Id) && !ids.Contains(z.Id) && z.Exit.Length > 0) notices.Add(z.Exit.Replace("{zone}", z.Name));
                        var client = ConnectionManager.Instance.Clients.ForEntityId(player.entityId);
                        if (client?.InternalId != null)
                            foreach (string message in notices.Take(3)) _bridge.SendPrivateMessage(client.InternalId.CombinedString, message);
                        if (notices.Count > 0) _noticeAfter[player.entityId] = Time.realtimeSinceStartup + 3f;
                    }
                    _inside[player.entityId] = ids;
                    var bonuses = ZoneBonuses.Strongest(active);
                    foreach (string legacy in LegacyBuffs)
                        if (player.Buffs.HasBuff(legacy)) player.Buffs.RemoveBuff(legacy);
                    for (int i = 0; i < Buffs.Length; i++)
                    {
                        float strength = bonuses.NativeValue(i);
                        bool wanted = i == 0 ? active.Any(z => z.NoDamage) : strength > 0;
                        if (i > 0 && (wanted || player.Buffs.HasCustomVar(StrengthVars[i])) &&
                            (!observed || player.Buffs.GetCustomVar(StrengthVars[i]) != strength))
                            player.Buffs.SetCustomVar(StrengthVars[i], strength, true, CVarOperation.set, true);
                        if (wanted) player.Buffs.AddBuff(Buffs[i]); // Short lease; expires if mod stops/fails.
                        else if (player.Buffs.HasBuff(Buffs[i])) player.Buffs.RemoveBuff(Buffs[i]);
                    }
                    // Denied players cannot trigger entry rewards; a structured portal owns this transition.
                    if (movement != null) RunMovement(player, clientInfo, movement, ref commandBudget);
                    else if (observed && holding == null)
                        foreach (var z in _rules.Zones)
                        {
                            bool entered = ids.Contains(z.Id) && !before!.Contains(z.Id);
                            bool exited = !ids.Contains(z.Id) && before!.Contains(z.Id);
                            if (z.IsActive && z.CommandsEnabled && (entered || exited))
                                RunCommands(player, z, entered, ref commandBudget);
                        }
                }
                foreach (int id in _inside.Keys.Where(id => !online.Contains(id)).ToArray()) { _inside.Remove(id); _noticeAfter.Remove(id); }
                _containment.Retain(online);
                foreach (int id in _returns.Keys.Where(id => !online.Contains(id)).ToArray()) _returns.Remove(id);
                // ponytail: bounded scan of loaded entities only (64/sec). Use a native movement hook if a measured need arises.
                if (_rules.Zones.Any(z => z.IsActive && z.Despawn != 0))
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

        private bool DestinationReady(ZoneMovement movement)
        {
            var world = GameManager.Instance.World;
            var destination = new Vector3(movement.X + 0.5f, movement.Y, movement.Z + 0.5f);
            // Native spawn check: loaded chunk, suitable floor, body/head space and no water.
            // Never load/generate a distant chunk synchronously just to service a portal.
            return world.InBoundsForPlayersPercent(destination) >= 0.5f &&
                world.CanPlayersSpawnAtPos(destination);
        }

        private void RunMovement(EntityPlayer player, ClientInfo? client, ZoneRule zone, ref int budget)
        {
            if (budget < 1 || client?.InternalId == null) return;
            var m = zone.Movement;
            if (!_commandGate.TryBegin(client.InternalId.CombinedString, zone.Id + "/movement", true,
                m.Cooldown, true, Time.realtimeSinceStartup)) return;
            budget--;
            try
            {
                // Count observed failed returns, not successful packet sends: the client may not move.
                if (m.IsContainment)
                {
                    if (!_returns.TryGetValue(player.entityId, out var attempts) || attempts.Zone != zone.Id)
                        _returns[player.entityId] = attempts = new ReturnAttempts { Zone = zone.Id, Since = Time.realtimeSinceStartup };
                    if (attempts.Count >= 3 && Time.realtimeSinceStartup - attempts.Since >= 30 && m.KickOnFailure)
                    {
                        new ConsoleCmdKick().Execute(new List<string> { player.entityId.ToString(System.Globalization.CultureInfo.InvariantCulture),
                            "Zone return failed. Please contact a server administrator." }, default(CommandSenderInfo));
                        Log.Warning("[AurumCompanion] Zone return failed repeatedly; strict kick: " + zone.Id + " entity=" + player.entityId);
                        return;
                    }
                    attempts.Count++;
                }
                if (!ZoneMovementPolicy.DestinationClear(_rules, m) || !DestinationReady(m))
                {
                    Log.Warning("[AurumCompanion] Zone movement skipped: " + zone.Id + " entity=" + player.entityId + " destination unavailable.");
                    return;
                }
                if (player.AttachedToEntity != null)
                {
                    // Explicit opt-in; leave the vehicle where it is and wait for native detach before teleport.
                    if (m.IsContainment && m.Dismount) player.SendDetach();
                    else Log.Warning("[AurumCompanion] Zone movement skipped: " + zone.Id + " entity=" + player.entityId + " player mounted.");
                    return;
                }
                // Public native packet, same path as teleportplayer; never call publicized protected helpers.
                LockManager.Instance.ForceUnlockByPlayer(player.entityId);
                client.SendPackage(NetPackageManager.GetPackage<NetPackageTeleportPlayer>().Setup(new Vector3(m.X + 0.5f, m.Y, m.Z + 0.5f)));
                if (m.Message.Length > 0) _bridge.SendPrivateMessage(client.InternalId.CombinedString, m.Message.Replace("{zone}", zone.Name));
                Log.Out("[AurumCompanion] Zone movement dispatched: " + zone.Id + " entity=" + player.entityId + " mode=" + m.Mode);
            }
            catch (Exception e) { Log.Error("[AurumCompanion] Zone movement failed (no immediate retry): " + zone.Id + " " + e.Message); }
        }

        private void RunCommands(EntityPlayer player, ZoneRule zone, bool entering, ref int budget)
        {
            var commands = entering ? zone.EnterCommands : zone.ExitCommands;
            if (commands.Length == 0) return;
            var client = ConnectionManager.Instance.Clients.ForEntityId(player.entityId);
            if (client?.InternalId == null) return;
            double now = Time.realtimeSinceStartup;
            if (commands.Length > budget)
            {
                if (now >= _commandWarningAfter) { Log.Warning("[AurumCompanion] Zone command budget reached; transition skipped, not queued."); _commandWarningAfter = now + 60; }
                return;
            }
            if (!_commandGate.TryBegin(client.InternalId.CombinedString, zone.Id, entering, zone.CommandCooldown,
                commands.Any(c => c.StartsWith("teleportplayer ", StringComparison.Ordinal)), now)) return;
            budget -= commands.Length;
            // Native handlers only; no command strings assembled from player names or chat.
            foreach (string command in commands)
            {
                try
                {
                    var p = ZoneCommands.Parse(command);
                    var args = p.Skip(1).ToList();
                    args[0] = player.entityId.ToString(System.Globalization.CultureInfo.InvariantCulture);
                    if (p[0] != "teleportplayer" && BuffManager.GetBuff(p[2]) == null) throw new InvalidOperationException("buff_missing");
                    ConsoleCmdAbstract handler = p[0] == "buffplayer" ? (ConsoleCmdAbstract)new ConsoleCmdBuffPlayer() :
                        p[0] == "debuffplayer" ? new ConsoleCmdDebuffPlayer() : new ConsoleCmdTeleportPlayer();
                    handler.Execute(args, default(CommandSenderInfo));
                    Log.Out("[AurumCompanion] Zone command dispatched: " + zone.Id + " " + (entering ? "enter" : "exit") + " entity=" + args[0] + " " + command);
                }
                catch (Exception e) { Log.Error("[AurumCompanion] Zone command failed (not retried): " + zone.Id + " " + e.Message); break; }
            }
        }

        private void PlayerSpawned(ref ModEvents.SPlayerSpawnedInWorldData data)
        {
            if (data.RespawnType != RespawnType.Teleport) { _inside.Remove(data.EntityId); _noticeAfter.Remove(data.EntityId); _containment.Remove(data.EntityId); _returns.Remove(data.EntityId); }
        }
        private void PlayerLeft(ref ModEvents.SPlayerDisconnectedData data)
        {
            if (data.ClientInfo != null) { _inside.Remove(data.ClientInfo.entityId); _noticeAfter.Remove(data.ClientInfo.entityId); _containment.Remove(data.ClientInfo.entityId); _returns.Remove(data.ClientInfo.entityId); }
        }
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
        // The reference assembly is publicized. These fields are private in the real game:
        // Harmony injects their values; never emit direct field access from our assembly.
        private static bool BeforePacket(World _world, int ___entityId, int ___attackerEntityId)
        {
            var current = Current;
            if (current == null || current._error != null || _world == null || _world.IsRemote()) return true;
            var victim = _world.GetEntity(___entityId) as EntityPlayer;
            if (victim == null) return true;
            var attacker = _world.GetEntity(___attackerEntityId);
            bool pvp = attacker is EntityPlayer && attacker.entityId != victim.entityId;
            return !current._rules.DenyDamage(victim.position.x, victim.position.z, pvp, attacker?.position.x ?? 0, attacker?.position.z ?? 0);
        }
        private static void AfterSpawn(World __instance, Entity _entity)
        {
            var current = Current;
            if (current == null || current._error != null || _entity == null || __instance.IsRemote() ||
                current._loadedWorld != __instance ||
                !current._rules.DenyCreature(_entity.position.x, _entity.position.z, Category(_entity), false)) return;
            if (!current._spawnChecks.TryAdd(_entity.entityId, _entity.entityClass, _entity.position.x, _entity.position.z) &&
                Time.realtimeSinceStartup >= current._spawnWarningAfter)
            {
                current._spawnWarningAfter = Time.realtimeSinceStartup + 60f;
                Log.Warning("[AurumCompanion] Zone spawn check queue full; excess checks skipped. No synchronous removal fallback.");
            }
        }
        private void CheckSpawnedEntities(ref ModEvents.SGameUpdateData data)
        {
            if (_spawnChecks.Count == 0) return;
            var world = GameManager.Instance?.World;
            if (_error != null || world == null || world != _loadedWorld) { _spawnChecks.Clear(); return; }
            try
            {
                // Runs after the spawning call stack, not from its postfix. Native callers
                // may set targets, counters, particles or quest state after SpawnEntityInWorld.
                // Newly queued checks during removal wait for another update.
                int count = Math.Min(ZoneSpawnChecks.PerUpdate, _spawnChecks.Count);
                for (int i = 0; i < count && _spawnChecks.TryTake(out var check); i++)
                {
                    var entity = world.GetEntity(check.EntityId);
                    if (entity != null && entity.entityClass == check.EntityClass &&
                        _rules.DenyCreature(check.X, check.Z, Category(entity), false))
                        world.RemoveEntity(check.EntityId, EnumRemoveEntityReason.Despawned);
                }
            }
            catch (Exception e)
            {
                _spawnChecks.Clear();
                _error = "zones_runtime_failed";
                Log.Error("[AurumCompanion] Deferred zone spawn check failed; zones inactive: " + e);
            }
        }
        public void Dispose()
        {
            ModEvents.GameUpdate.UnregisterHandler(Update);
            ModEvents.GameUpdate.UnregisterHandler(CheckSpawnedEntities);
            ModEvents.PlayerSpawnedInWorld.UnregisterHandler(PlayerSpawned);
            ModEvents.PlayerDisconnected.UnregisterHandler(PlayerLeft);
            if (Current == this) Current = null;
            Unpatch();
            _inside.Clear(); _noticeAfter.Clear();
            _commandGate.Clear();
            _containment.Clear(); _returns.Clear();
            _spawnChecks.Clear(); _loadedWorld = null;
        }
        private void Unpatch()
        {
            foreach (var method in _patched) _harmony.Unpatch(method, HarmonyPatchType.All, _harmony.Id);
            _patched.Clear();
        }
    }
}
