using System;
using System.IO;
using Aurum.Companion.Core.Game;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class NativeMapFilesTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "aurum-map-test-" + Guid.NewGuid().ToString("N"));
    public NativeMapFilesTests() { Directory.CreateDirectory(_root); }
    public void Dispose() { Directory.Delete(_root, true); }
    private NativeMapFiles Files => new(_root);
    private void Info(string text = "{\"blockSize\":128,\"maxZoom\":4}") => File.WriteAllText(Path.Combine(_root, "mapinfo.json"), text);
    private void Tile(byte[] bytes)
    {
        Directory.CreateDirectory(Path.Combine(_root, "4", "-1"));
        File.WriteAllBytes(Path.Combine(_root, "4", "-1", "-2.png"), bytes);
    }
    private static byte[] Header(int size = 128)
    {
        var b = new byte[33]; new byte[] {137,80,78,71,13,10,26,10}.CopyTo(b, 0);
        new byte[] {73,72,68,82}.CopyTo(b, 12); b[18] = b[22] = (byte)(size >> 8); b[19] = b[23] = (byte)size; return b;
    }
    [Fact] public void Missing_files_are_normal_and_do_not_generate_map()
    {
        Assert.Contains("native_map_missing", Files.Info());
        Assert.Equal("{\"png\":null}", Files.Tile(4, -1, -2));
        Assert.Empty(Directory.GetFiles(_root));
    }
    [Theory]
    [InlineData("broken")]
    [InlineData("{\"blockSize\":129,\"maxZoom\":4}")]
    [InlineData("{\"blockSize\":128,\"maxZoom\":9}")]
    public void Invalid_metadata_is_not_used(string text) { Info(text); Assert.Contains("native_map_missing", Files.Info()); }
    [Fact] public void Oversized_metadata_is_rejected() { Info(new string(' ', 4097)); Assert.Contains("native_map_missing", Files.Info()); }
    [Fact] public void Negative_numeric_paths_are_supported()
    {
        Info(); var bytes = Header(); Tile(bytes);
        Assert.Contains("\"available\":true", Files.Info());
        Assert.Contains(Convert.ToBase64String(bytes), Files.Tile(4,-1,-2));
    }
    [Fact] public void Corrupt_wrong_dimensions_and_oversized_tiles_are_rejected()
    {
        Info();
        foreach (var bytes in new[] { new byte[33], Header(512), new byte[262145] })
        { Tile(bytes); Assert.Equal("{\"png\":null}", Files.Tile(4,-1,-2)); }
    }
    [Theory]
    [InlineData(9,0,0)] [InlineData(4,65537,0)] [InlineData(4,0,-65537)]
    public void Invalid_coordinates_never_become_paths(int z,int x,int y) => Assert.Throws<ArgumentOutOfRangeException>(() => Files.Tile(z,x,y));
    [Fact] public void Above_native_zoom_is_missing() { Info(); Assert.Equal("{\"png\":null}", Files.Tile(5,0,0)); }
}
