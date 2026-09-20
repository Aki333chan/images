using System;
using System.IO;

namespace Aurum.Companion.Core.Game
{
    /// <summary>Read-only, bounded primary save. Never silently reads .bak or calls Save.</summary>
    public sealed class SavedPlayerFile
    {
        public const int MaxBytes = 8 * 1024 * 1024;
        public byte[] Bytes { get; }
        public DateTime SavedAt { get; }
        private readonly string _path;
        private SavedPlayerFile(string path, byte[] bytes, DateTime savedAt)
        { _path = path; Bytes = bytes; SavedAt = savedAt; }

        public static SavedPlayerFile Read(string root, string id)
        {
            if (!InventoryRequest.ValidPlayerId(id)) throw new InvalidDataException("invalid_player_id");
            root = Path.GetFullPath(root);
            // Reject symlink/reparse components, including a redirected save directory.
            for (var dir = new DirectoryInfo(root); dir != null; dir = dir.Parent)
                if ((dir.Attributes & FileAttributes.ReparsePoint) != 0) throw new IOException("save_unavailable");
            string path = Path.Combine(root, id + ".ttp");
            var info = new FileInfo(path);
            if (!info.Exists) throw new FileNotFoundException();
            if ((info.Attributes & FileAttributes.ReparsePoint) != 0) throw new IOException("save_unavailable");
            if (info.Length < 5 || info.Length > MaxBytes) throw new InvalidDataException("save_invalid");
            if (File.Exists(path + ".tmp") || DateTime.UtcNow - info.LastWriteTimeUtc < TimeSpan.FromSeconds(5))
                throw new IOException("save_busy");
            var bytes = new byte[(int)info.Length];
            using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                int offset = 0;
                while (offset < bytes.Length)
                {
                    int n = stream.Read(bytes, offset, bytes.Length - offset);
                    if (n == 0) throw new IOException("save_busy");
                    offset += n;
                }
                if (stream.ReadByte() != -1) throw new IOException("save_busy");
            }
            // This release supports the verified save schema only; never guess newer layouts.
            if (bytes[0] != 't' || bytes[1] != 't' || bytes[2] != 'p' || bytes[3] != 0 || bytes[4] != 59)
                throw new InvalidDataException("save_invalid");
            var result = new SavedPlayerFile(path, bytes, info.LastWriteTimeUtc);
            if (!result.Unchanged()) throw new IOException("save_busy");
            return result;
        }
        public bool Unchanged()
        {
            var current = new FileInfo(_path);
            return current.Exists && (current.Attributes & FileAttributes.ReparsePoint) == 0 &&
                current.Length == Bytes.Length && current.LastWriteTimeUtc == SavedAt && !File.Exists(_path + ".tmp");
        }
    }

    public interface ISavedInventoryBridge
    {
        string SavedPlayers(string query, int offset);
        string ReadSavedInventory(string playerId);
    }
}
