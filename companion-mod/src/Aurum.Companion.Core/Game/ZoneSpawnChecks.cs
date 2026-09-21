using System.Collections.Generic;

namespace Aurum.Companion.Core.Game
{
    // A bounded, game-thread-only handoff. Never retain Unity entities or run removal
    // inside SpawnEntityInWorld: its caller still owns spawn bookkeeping/callbacks.
    public sealed class ZoneSpawnChecks
    {
        public const int Capacity = 1024;
        public const int PerUpdate = 64;
        private readonly Queue<Check> _queue = new Queue<Check>();
        private readonly HashSet<int> _ids = new HashSet<int>();
        public int Count => _queue.Count;

        public readonly struct Check
        {
            public readonly int EntityId, EntityClass;
            public readonly double X, Z;
            public Check(int entityId, int entityClass, double x, double z)
            { EntityId = entityId; EntityClass = entityClass; X = x; Z = z; }
        }

        public bool TryAdd(int entityId, int entityClass, double x, double z)
        {
            if (_ids.Contains(entityId)) return true;
            if (_queue.Count >= Capacity) return false;
            _ids.Add(entityId);
            _queue.Enqueue(new Check(entityId, entityClass, x, z));
            return true;
        }

        public bool TryTake(out Check check)
        {
            if (_queue.Count == 0) { check = default; return false; }
            check = _queue.Dequeue();
            _ids.Remove(check.EntityId);
            return true;
        }

        public void Clear() { _queue.Clear(); _ids.Clear(); }
    }
}
