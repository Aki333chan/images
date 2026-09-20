using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace Aurum.Companion.Core.Game
{
    /// <summary>Read-only, bounded primary save. Never silently reads .bak or calls Save.</summary>
    public sealed class SavedPlayerFile
    {
        public const int MaxBytes = 8 * 1024 * 1024;
        public byte[] Bytes { get; }
        public DateTime SavedAt { get; }
        public string Revision { get; }
        private readonly string _path;
        private SavedPlayerFile(string path, byte[] bytes, DateTime savedAt)
        { _path = path; Bytes = bytes; SavedAt = savedAt; Revision = Fingerprint(path, bytes); }

        private static string Fingerprint(string path, byte[] bytes)
        {
            using (var sha = SHA256.Create())
            {
                byte[] context = Encoding.UTF8.GetBytes(path + "\0");
                sha.TransformBlock(context, 0, context.Length, context, 0);
                sha.TransformFinalBlock(bytes, 0, bytes.Length);
                return BitConverter.ToString(sha.Hash!).Replace("-", "").ToLowerInvariant();
            }
        }

        // Caller must serialize this with native player load/save (main game thread in V3.2 b10).
        // Never use this as a lock against arbitrary external save-file editors.
        public void Replace(byte[] replacement, string requestId)
        {
            if (!Guid.TryParseExact(requestId, "D", out _)) throw new InvalidDataException("invalid_request");
            if (replacement.Length < 5 || replacement.Length > MaxBytes) throw new InvalidDataException("save_invalid");
            for (int i = 0; i < 5; i++) if (replacement[i] != Bytes[i]) throw new InvalidDataException("save_invalid");
            string root = Path.GetDirectoryName(_path)!;
            string id = Path.GetFileNameWithoutExtension(_path);
            if (Read(root, id).Revision != Revision) throw new IOException("revision_conflict");
            string backup = _path + ".aurum-" + requestId.ToLowerInvariant() + ".bak";
            string pending = _path + ".aurum-" + requestId.ToLowerInvariant() + ".tmp";
            if (File.Exists(backup) || File.Exists(pending)) throw new IOException("request_exists");
            // ponytail: 100 backups per world, no automatic deletion; explicit archive UI if needed later.
            int count = 0;
            foreach (string unused in Directory.EnumerateFiles(root, "*.ttp.aurum-*.bak"))
                if (++count >= 100) throw new IOException("backup_limit");
            bool created = false;
            try
            {
                using (var stream = new FileStream(pending, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                { created = true; stream.Write(replacement, 0, replacement.Length); stream.Flush(true); }
                if (Read(root, id).Revision != Revision) throw new IOException("revision_conflict");
                // Same directory/filesystem. On unsupported filesystems fail, never fall back to copy-overwrite.
                try { File.Replace(pending, _path, backup); }
                catch (IOException e) { throw new IOException("save_commit_unknown", e); }
            }
            finally { if (created && File.Exists(pending)) File.Delete(pending); }
        }

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
        string ReduceSavedStack(string playerId, string revision, string section, int slot, int count, string requestId);
    }
}
