using System;
using Aurum.Companion.Core.Game;
namespace Aurum.Companion.Game
{
    // Native Harmony hooks are checked ONLY by the real-game build and the live smoke matrix.
    internal sealed class ZoneRuntime : IDisposable
    {
        public ZoneRuntime(SdtdGameBridge bridge) { }
        public string Read() => throw new NotSupportedException("stub");
        public string Save(ZoneRules rules) => throw new NotSupportedException("stub");
        public void Dispose() { }
    }
}
