using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using System.Reflection.Emit;
using HarmonyLib;
using UnityEngine;

namespace Aurum.Companion.Game
{
    // Only wandering hordes. Blood moon, sleepers and quests have different accounting.
    internal static class ZoneWanderingSpawnPatch
    {
        internal static readonly MethodInfo Target = AccessTools.Method(typeof(AIWanderingHordeSpawner), "UpdateSpawn",
            new[] { typeof(World), typeof(float) });

        internal static IEnumerable<CodeInstruction> Transpile(IEnumerable<CodeInstruction> instructions, ILGenerator generator)
        {
            var code = instructions.ToList();
            var create = AccessTools.Method(typeof(EntityFactory), "CreateEntity", new[] { typeof(int), typeof(Vector3) });
            if (Target == null || Target.IsStatic || Target.ReturnType != typeof(bool) ||
                Target.GetMethodBody()!.ExceptionHandlingClauses.Count != 0 || code.Any(c => c.blocks.Count != 0) ||
                create == null || !create.IsStatic || create.ReturnType != typeof(Entity))
                throw new InvalidOperationException("Unsupported wandering spawn body");
            int[] calls = code.Select((c, i) => c.Calls(create) ? i : -1).Where(i => i >= 0).ToArray();
            if (calls.Length != 1 || calls[0] < 3 || code[calls[0] - 3].opcode != OpCodes.Ret ||
                !code[calls[0] - 2].IsLdloc() || !code[calls[0] - 1].IsLdloc())
                throw new InvalidOperationException("Unsupported wandering spawn sequence");
            int at = calls[0] - 2;
            var resume = generator.DefineLabel();
            var first = new CodeInstruction(OpCodes.Ldarg_1); // Native World argument, no private field access.
            first.labels.AddRange(code[at].labels);
            code[at].labels.Clear();
            code[at].labels.Add(resume);
            code.InsertRange(at, new[] {
                first, new CodeInstruction(code[at].opcode, code[at].operand),
                new CodeInstruction(code[at + 1].opcode, code[at + 1].operand),
                new CodeInstruction(OpCodes.Call, AccessTools.Method(typeof(ZoneRuntime), "DenyEarlySpawn")),
                new CodeInstruction(OpCodes.Brfalse, resume),
                // Same result as a failed native position search. The existing one-second
                // delay/end time stay intact; no entity, loot counter or wave count is added.
                new CodeInstruction(OpCodes.Ldc_I4_0), new CodeInstruction(OpCodes.Ret)
            });
            return code;
        }
    }
}
