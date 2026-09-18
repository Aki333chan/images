using System;
using System.Globalization;
using System.IO;
using System.Text;
using Aurum.Companion.Core.Json;

namespace Aurum.Companion.Core.Game
{
    public interface IMapBridge
    {
        string ReadMapInfo();
        string ReadMapMarkers();
        string ReadMapTile(int zoom, int x, int z);
    }

    /// <summary>Existing native files only. No chunk loading/rendering; call off the game thread.</summary>
    public sealed class NativeMapFiles
    {
        private readonly string _root;
        public NativeMapFiles(string root) { _root = Path.GetFullPath(root); }
        public string Info()
        {
            if (!TryInfo(out int size, out int zoom)) return "{\"available\":false,\"reason\":\"native_map_missing\"}";
            return "{\"available\":true,\"blockSize\":" + size + ",\"maxZoom\":" + zoom + "}";
        }
        private bool TryInfo(out int size, out int zoom)
        {
            size = zoom = 0;
            try
            {
                var map = JsonReader.ParseObject(Encoding.UTF8.GetString(ReadBounded(Path.Combine(_root, "mapinfo.json"), 4096)));
                if (!map.TryGetValue("blockSize", out var s) || !(s is double n) || n < 64 || n > 512 || n != (int)n || (((int)n & ((int)n - 1)) != 0)) return false;
                if (!map.TryGetValue("maxZoom", out var z) || !(z is double m) || m < 0 || m > 8 || m != (int)m) return false;
                size = (int)n; zoom = (int)m; return true;
            }
            catch (IOException) { return false; }
            catch (UnauthorizedAccessException) { return false; }
            catch (JsonReader.JsonException) { return false; }
        }
        public static bool ValidCoordinates(int zoom, int x, int z) => zoom >= 0 && zoom <= 8 && x >= -65536 && x <= 65536 && z >= -65536 && z <= 65536;
        public string Tile(int zoom, int x, int z)
        {
            if (!ValidCoordinates(zoom, x, z)) throw new ArgumentOutOfRangeException(nameof(zoom));
            if (!TryInfo(out int size, out int maxZoom) || zoom > maxZoom) return "{\"png\":null}";
            try
            {
                string path = Path.Combine(_root, zoom.ToString(CultureInfo.InvariantCulture), x.ToString(CultureInfo.InvariantCulture), z.ToString(CultureInfo.InvariantCulture) + ".png");
                byte[] png = ReadBounded(path, 256 * 1024);
                byte[] signature = { 137, 80, 78, 71, 13, 10, 26, 10 };
                if (png.Length < 33) return "{\"png\":null}";
                for (int i = 0; i < signature.Length; i++) if (png[i] != signature[i]) return "{\"png\":null}";
                if (png[12] != 73 || png[13] != 72 || png[14] != 68 || png[15] != 82 || ReadInt(png,16) != size || ReadInt(png,20) != size) return "{\"png\":null}";
                return "{\"png\":\"" + Convert.ToBase64String(png) + "\"}";
            }
            catch (IOException) { return "{\"png\":null}"; }
            catch (UnauthorizedAccessException) { return "{\"png\":null}"; }
        }
        private static int ReadInt(byte[] b, int i) => (b[i] << 24) | (b[i+1] << 16) | (b[i+2] << 8) | b[i+3];
        private byte[] ReadBounded(string path, int limit)
        {
            // Integer-only filenames, and no symlinks beneath the trusted save root.
            for (string? current = path; current != null; current = Path.GetDirectoryName(current))
            {
                if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0) throw new IOException("map_link");
                if (string.Equals(current, _root, StringComparison.OrdinalIgnoreCase)) break;
            }
            using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
            {
                if (stream.Length < 1 || stream.Length > limit) throw new IOException("map_size");
                var bytes = new byte[(int)stream.Length];
                int offset = 0;
                while (offset < bytes.Length) { int read = stream.Read(bytes, offset, bytes.Length-offset); if (read == 0) throw new IOException("map_incomplete"); offset += read; }
                return bytes;
            }
        }
    }
}
