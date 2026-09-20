using System;
using System.IO;
using Aurum.Companion.Core.Game;
using Xunit;

public sealed class SavedPlayerFileTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "aurum-save-test-" + Guid.NewGuid());
    private const string Id = "Steam_123";
    public SavedPlayerFileTests() { Directory.CreateDirectory(_root); }
    private string Seed(byte[]? bytes = null)
    {
        string file = Path.Combine(_root, Id + ".ttp");
        File.WriteAllBytes(file, bytes ?? new byte[] { 116, 116, 112, 0, 59, 1 });
        File.SetLastWriteTimeUtc(file, DateTime.UtcNow.AddMinutes(-1));
        return file;
    }
    [Fact] public void Reads_primary_without_changing_any_file()
    {
        var file = Seed(); var before = File.ReadAllBytes(file); var time = File.GetLastWriteTimeUtc(file);
        var read = SavedPlayerFile.Read(_root, Id);
        Assert.Equal(before, read.Bytes); Assert.Equal(time, read.SavedAt); Assert.True(read.Unchanged());
        Assert.Equal(before, File.ReadAllBytes(file)); Assert.Single(Directory.GetFiles(_root));
    }
    [Theory] [InlineData("../Steam_123")] [InlineData("Steam_1/other")] [InlineData("Steam_1\\other")] [InlineData("Steam_123.ttp")]
    public void Rejects_paths(string id) { Assert.Throws<InvalidDataException>(() => SavedPlayerFile.Read(_root, id)); }
    [Fact] public void Never_falls_back_to_backup()
    {
        File.Move(Seed(), Path.Combine(_root, Id + ".ttp.bak"));
        Assert.Throws<FileNotFoundException>(() => SavedPlayerFile.Read(_root, Id));
    }
    [Fact] public void Rejects_recent_and_in_progress_save()
    {
        var file = Seed(); File.SetLastWriteTimeUtc(file, DateTime.UtcNow);
        Assert.Throws<IOException>(() => SavedPlayerFile.Read(_root, Id));
        Seed(); File.WriteAllText(file + ".tmp", "writing");
        Assert.Throws<IOException>(() => SavedPlayerFile.Read(_root, Id));
    }
    [Fact] public void Rejects_unknown_schema_and_oversized_file()
    {
        Seed(new byte[] {116,116,112,0,60});
        Assert.Throws<InvalidDataException>(() => SavedPlayerFile.Read(_root, Id));
        var file = Seed(); using (var stream = File.OpenWrite(file)) stream.SetLength(SavedPlayerFile.MaxBytes + 1);
        Assert.Throws<InvalidDataException>(() => SavedPlayerFile.Read(_root, Id));
    }
    [Fact] public void Detects_later_write_or_removal()
    {
        var file = Seed(); var read = SavedPlayerFile.Read(_root, Id);
        File.AppendAllText(file, "changed"); Assert.False(read.Unchanged());
        File.Delete(file); Assert.False(read.Unchanged());
    }
    public void Dispose() { Directory.Delete(_root, true); }
}
