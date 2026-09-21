using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Reflection.Emit;
using System.Runtime.CompilerServices;
using System.Collections.Generic;
using HarmonyLib;

// Offline only. Transforms supplied native IL; never invokes/patches a live game spawner.
// Requires the supplied server DLLs and Harmony's sibling dependencies, never copies/ships them.
internal static class Program
{
    private static void Main(string[] args)
    {
        if (args.Length != 3) throw new ArgumentException("managed-directory harmony-dll companion-dll");
        string[] roots = { Path.GetFullPath(args[0]), Path.GetDirectoryName(Path.GetFullPath(args[1]))!, Path.GetDirectoryName(Path.GetFullPath(args[2]))! };
        AppDomain.CurrentDomain.AssemblyResolve += (_, request) => {
            string name = new AssemblyName(request.Name).Name + ".dll";
            string? path = roots.Select(root => Path.Combine(root, name)).FirstOrDefault(File.Exists);
            return path == null ? null : Assembly.LoadFrom(path);
        };
        Check(args);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void Check(string[] args)
    {
        var game = Assembly.LoadFrom(Path.Combine(args[0], "Assembly-CSharp.dll"));
        var mod = Assembly.LoadFrom(args[2]);
        var target = game.GetType("SpawnManagerBiomes")!.GetMethod("SpawnUpdate", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)!;
        var transpiler = mod.GetType("Aurum.Companion.Game.ZoneBiomeSpawnPatch")!.GetMethod("Transpile", BindingFlags.Static | BindingFlags.NonPublic)!;
        List<CodeInstruction> Apply(List<CodeInstruction> code, ILGenerator generator) =>
            ((IEnumerable<CodeInstruction>)transpiler.Invoke(null, new object[] { code, generator })!).ToList();
        var original = ReadBody(target, out var gen);
        var rewritten = Apply(original, gen);
        Require(rewritten.Count == original.Count + 9, "Unexpected guard size");
        var gates = rewritten.Where(c => c.operand is MethodInfo m && m.Name == "DenyEarlySpawn").ToArray();
        Require(gates.Length == 1, "Expected one source-specific guard");
        int at = rewritten.IndexOf(gates[0]);
        Require(string.Join(",", rewritten.Skip(at + 1).Take(5).Select(c => c.opcode.Name)) == "brfalse,pop,pop,ret,callvirt", "Unsafe stack/branch sequence");
        Require(((MethodInfo)rewritten[at + 5].operand).Name == "IncCount", "Not before counter increment");
        Require(rewritten[at + 5].labels.Contains((Label)rewritten[at + 1].operand), "Allow branch bypasses native counter");
        foreach (string mode in new[] { "missing-count", "duplicate-count", "moved-setup", "nonlocal-position" })
        {
            var code = ReadBody(target, out gen);
            int count = code.FindIndex(c => c.operand is MethodInfo m && m.Name == "IncCount");
            switch (mode)
            {
                case "missing-count": code.RemoveAt(count); break;
                case "duplicate-count": code.Add(new CodeInstruction(code[count])); break;
                case "moved-setup": code.Insert(count + 1, new CodeInstruction(OpCodes.Nop)); break;
                case "nonlocal-position": code[count + 2] = new CodeInstruction(OpCodes.Ldnull); break;
            }
            bool rejected = false;
            try { Apply(code, gen); }
            catch (TargetInvocationException e) when (e.InnerException is InvalidOperationException inner && inner.Message.StartsWith("Unsupported biome spawn")) { rejected = true; }
            Require(rejected, "Changed body accepted: " + mode);
        }
        Console.WriteLine("PASS: supplied biome IL transformation, early-return stack/branch, four incompatible-body refusals. Native Harmony application and gameplay remain live checks.");
        CheckWandering(game, mod);
        CheckTraderProtection(game, mod);
    }

    private static void CheckTraderProtection(Assembly game, Assembly mod)
    {
        var vector = game.GetType("Vector3i") ?? AppDomain.CurrentDomain.GetAssemblies().Select(a => a.GetType("Vector3i")).First(t => t != null)!;
        object V(int x, int y, int z) => Activator.CreateInstance(vector, x, y, z)!;
        var area = game.GetType("TraderArea")!;
        var constructor = area.GetConstructors().Single();
        var volumes = Activator.CreateInstance(constructor.GetParameters()[3].ParameterType, new object?[] { null });
        object Area(int x) => constructor.Invoke(new[] { V(x, 0, -20), V(20, 256, 40), V(-2, 0, -2), volumes });
        var real = Area(100); var ours = Area(-10);
        var protectPos = area.GetField("ProtectPosition")!.GetValue(ours)!;
        var protectSize = area.GetField("ProtectSize")!.GetValue(ours)!;
        Require((int)vector.GetField("x")!.GetValue(protectPos)! == -10 && (int)vector.GetField("z")!.GetValue(protectPos)! == -20, "Trader protection shifted origin");
        Require((int)vector.GetField("x")!.GetValue(protectSize)! == 20 && (int)vector.GetField("z")!.GetValue(protectSize)! == 40, "Trader constructor padding changed");
        Require(!(bool)area.GetProperty("IsInitialized")!.GetValue(ours)!, "Protection acquired a trader");
        var listType = typeof(List<>).MakeGenericType(area);
        var original = (System.Collections.IList)Activator.CreateInstance(listType)!;
        original.Add(real); original.Add(ours);
        var runtimeType = mod.GetType("Aurum.Companion.Game.ZoneTraderRuntime")!;
        var runtime = Activator.CreateInstance(runtimeType, true)!;
        ((System.Collections.IList)runtimeType.GetField("_areas", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(runtime)!).Add(ours);
        var filtered = (System.Collections.IList)runtimeType.GetMethod("ForAdmin", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(runtime, new object[] { original })!;
        Require(filtered.Count == 1 && ReferenceEquals(filtered[0], real), "Admin filtering removed native traders");
        Require(original.Count == 2, "Admin filtering mutated the shared player list");
        var write = ReadBody(area.GetMethod("Write")!, out _);
        var read = ReadBody(area.GetMethod("Read")!, out _);
        Require(write.Count(c => c.opcode == OpCodes.Conv_I2) == 3 && write.Count(c => c.opcode == OpCodes.Conv_I1) >= 3, "Native trader dimensions wire widths changed");
        Require(read.Count(c => c.operand is Mono.Cecil.MethodReference m && m.Name == "ReadInt16") == 3, "Native trader size decoder changed");
        Console.WriteLine("PASS: native trader constructor geometry, no owning NPC, admin-only list filtering without shared mutation, native size codec contract. Not a client/Harmony gameplay test.");
    }

    private static void CheckWandering(Assembly game, Assembly mod)
    {
        var target = game.GetType("AIWanderingHordeSpawner")!.GetMethod("UpdateSpawn", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)!;
        var transpiler = mod.GetType("Aurum.Companion.Game.ZoneWanderingSpawnPatch")!.GetMethod("Transpile", BindingFlags.Static | BindingFlags.NonPublic)!;
        List<CodeInstruction> Apply(List<CodeInstruction> code, ILGenerator generator) =>
            ((IEnumerable<CodeInstruction>)transpiler.Invoke(null, new object[] { code, generator })!).ToList();
        var original = ReadBody(target, out var gen);
        int factory = original.FindIndex(c => c.operand is MethodInfo m && m.Name == "CreateEntity");
        var entryLabels = original[factory - 2].labels.ToArray();
        // This exact supplied method has an empty stack before the two local loads:
        // preceding branch is the native missing-class return, not an enclosing call.
        Require(original[factory - 3].opcode == OpCodes.Ret, "Unproven entry stack");
        var rewritten = Apply(original, gen);
        Require(rewritten.Count == original.Count + 7, "Unexpected wandering guard size");
        int at = rewritten.FindIndex(c => c.operand is MethodInfo m && m.Name == "DenyEarlySpawn");
        Require(at == factory + 1, "Wrong wandering guard position");
        Require(rewritten[at - 3].opcode == OpCodes.Ldarg_1, "World argument not loaded");
        Require(entryLabels.All(l => rewritten[at - 3].labels.Contains(l)), "Native entry can bypass guard");
        Require(string.Join(",", rewritten.Skip(at + 1).Take(3).Select(c => c.opcode.Name)) == "brfalse,ldc.i4.0,ret", "Unsafe wandering return");
        Require(rewritten[at + 4].labels.Contains((Label)rewritten[at + 1].operand), "Allow branch skips factory arguments");
        Require(((MethodInfo)rewritten[at + 6].operand).Name == "CreateEntity", "Allow branch skips native factory");
        foreach (string mode in new[] { "missing-factory", "duplicate-factory", "nonlocal-class", "nonlocal-position", "exception-block", "unproven-stack" })
        {
            var code = ReadBody(target, out gen);
            switch (mode)
            {
                case "missing-factory": code.RemoveAt(factory); break;
                case "duplicate-factory": code.Add(new CodeInstruction(code[factory])); break;
                case "nonlocal-class": code[factory - 2] = new CodeInstruction(OpCodes.Ldc_I4_0); break;
                case "nonlocal-position": code[factory - 1] = new CodeInstruction(OpCodes.Ldnull); break;
                case "exception-block": code[factory].blocks.Add(new ExceptionBlock(ExceptionBlockType.BeginExceptionBlock)); break;
                case "unproven-stack": code[factory - 3] = new CodeInstruction(OpCodes.Nop); break;
            }
            bool rejected = false;
            try { Apply(code, gen); }
            catch (TargetInvocationException e) when (e.InnerException is InvalidOperationException inner && inner.Message.StartsWith("Unsupported wandering spawn")) { rejected = true; }
            Require(rejected, "Changed wandering body accepted: " + mode);
        }
        Console.WriteLine("PASS: supplied wandering IL, empty-stack early false return, native entry/allow labels, six incompatible-body refusals. Not a live Harmony test.");
    }
    private static List<CodeInstruction> ReadBody(MethodInfo target, out ILGenerator generator)
    {
        // The server's HarmonyX/MonoMod build targets Unity Mono. Its detour backend does
        // not run on desktop CoreCLR. Read actual IL with its existing Cecil dependency,
        // then invoke the production transpiler; do not substitute guessed instructions.
        using var assembly = Mono.Cecil.AssemblyDefinition.ReadAssembly(target.Module.FullyQualifiedName);
        var method = (Mono.Cecil.MethodDefinition)assembly.MainModule.LookupToken(target.MetadataToken);
        generator = new DynamicMethod("biome_il_probe", typeof(void), Type.EmptyTypes, true).GetILGenerator();
        var il = generator;
        var locals = target.GetMethodBody()!.LocalVariables.Select(v => il.DeclareLocal(v.LocalType)).ToArray();
        var opcodes = typeof(OpCodes).GetFields(BindingFlags.Public | BindingFlags.Static)
            .Select(f => (OpCode)f.GetValue(null)!).ToDictionary(op => op.Name!);
        var labels = new Dictionary<Mono.Cecil.Cil.Instruction, Label>();
        foreach (var instruction in method.Body.Instructions) labels[instruction] = generator.DefineLabel();
        var result = new List<CodeInstruction>();
        foreach (var instruction in method.Body.Instructions)
        {
            object? operand = instruction.Operand;
            if (operand is Mono.Cecil.MethodReference reference &&
                (reference.Name == "IncCount" || reference.Name == "SetupEntityCreationData" || reference.Name == "CreateEntity"))
                operand = target.Module.ResolveMethod(reference.MetadataToken.ToInt32());
            else if (operand is Mono.Cecil.Cil.VariableDefinition variable) operand = locals[variable.Index];
            else if (operand is Mono.Cecil.Cil.Instruction branch) operand = labels[branch];
            var code = new CodeInstruction(opcodes[instruction.OpCode.Name], operand);
            code.labels.Add(labels[instruction]);
            result.Add(code);
        }
        return result;
    }
    private static void Require(bool condition, string message) { if (!condition) throw new InvalidOperationException(message); }
}
