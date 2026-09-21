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
        var gates = rewritten.Where(c => c.operand is MethodInfo m && m.Name == "DenyBiomeSpawn").ToArray();
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
                (reference.Name == "IncCount" || reference.Name == "SetupEntityCreationData"))
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
