using System;
using Aurum.Companion.Core.Game;

namespace Aurum.Companion.Game
{
    internal sealed partial class SdtdGameBridge : IZoneBridge
    {
        private IDisposable? _zones;
        private Func<string>? _readZones;
        private Func<ZoneRules, string>? _saveZones;
        // Keep Harmony loading isolated: a missing optional runtime must not disable tickets/map.
        [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
        internal void StartZones()
        {
            var runtime = new ZoneRuntime(this);
            _zones = runtime; _readZones = runtime.Read; _saveZones = runtime.Save;
        }
        internal void StopZones() { _zones?.Dispose(); _zones = null; _readZones = null; _saveZones = null; }
        public string ReadZones() => MainThread.Get(() => (_readZones ?? throw new InvalidOperationException("zones_runtime_unavailable"))());
        public string SaveZones(ZoneRules rules) => MainThread.Get(() => (_saveZones ?? throw new InvalidOperationException("zones_runtime_unavailable"))(rules));
    }
}
