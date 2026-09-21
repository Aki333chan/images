using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using System.Reflection.Emit;
using HarmonyLib;
using UnityEngine;

namespace Aurum.Companion.Game
{
    // Source-specific gate, NOT an EntityFactory/Chunk hook. Refuse an unfamiliar IL shape.
    internal static class ZoneBiomeSpawnPatch
    {
        internal static readonly MethodInfo Target = AccessTools.Method(typeof(SpawnManagerBiomes), "SpawnUpdate",
            new[] { typeof(string), typeof(bool), typeof(ChunkAreaBiomeSpawnData) });

        internal static IEnumerable<CodeInstruction> Transpile(IEnumerable<CodeInstruction> instructions, ILGenerator generator)
        {
            var code = instructions.ToList();
            var count = AccessTools.Method(typeof(ChunkAreaBiomeSpawnData), "IncCount", new[] { typeof(int) });
            var setup = AccessTools.Method(typeof(EntityFactory), "SetupEntityCreationData", new[] { typeof(int), typeof(Vector3) });
            var world = AccessTools.Field(typeof(SpawnManagerAbstract), "world");
            if (Target == null || Target.IsStatic || Target.ReturnType != typeof(void) || Target.GetMethodBody()!.ExceptionHandlingClauses.Count != 0 ||
                count == null || count.IsStatic || count.ReturnType != typeof(void) || setup == null || !setup.IsStatic ||
                setup.ReturnType != typeof(EntityCreationData) || world == null || world.IsStatic || world.FieldType != typeof(World) ||
                code.Any(c => c.blocks.Count != 0)) throw new InvalidOperationException("Unsupported biome spawn body");
            int[] counts = code.Select((c, i) => c.Calls(count) ? i : -1).Where(i => i >= 0).ToArray();
            int[] setups = code.Select((c, i) => c.Calls(setup) ? i : -1).Where(i => i >= 0).ToArray();
            // V3.2b10: IncCount; ldloc classId; ldloc position; SetupEntityCreationData.
            // Reuse those exact local loads; never hardcode local indices across versions.
            if (counts.Length != 1 || setups.Length != 1 || setups[0] != counts[0] + 3 ||
                !code[counts[0] + 1].IsLdloc() || !code[counts[0] + 2].IsLdloc())
                throw new InvalidOperationException("Unsupported biome spawn sequence");
            int at = counts[0];
            var resume = generator.DefineLabel();
            var first = new CodeInstruction(OpCodes.Ldarg_0);
            first.labels.AddRange(code[at].labels);
            code[at].labels.Clear();
            code[at].labels.Add(resume);
            var guard = new[] {
                first, new CodeInstruction(OpCodes.Ldfld, world),
                new CodeInstruction(code[at + 1].opcode, code[at + 1].operand),
                new CodeInstruction(code[at + 2].opcode, code[at + 2].operand),
                new CodeInstruction(OpCodes.Call, AccessTools.Method(typeof(ZoneRuntime), "DenyEarlySpawn")),
                new CodeInstruction(OpCodes.Brfalse, resume),
                // IncCount's receiver and argument are still on the stack. No count changed yet.
                new CodeInstruction(OpCodes.Pop), new CodeInstruction(OpCodes.Pop), new CodeInstruction(OpCodes.Ret)
            };
            code.InsertRange(at, guard);
            return code;
        }
    }
}
