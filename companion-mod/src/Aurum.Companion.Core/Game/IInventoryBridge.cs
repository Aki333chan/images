using System.Text.RegularExpressions;

namespace Aurum.Companion.Core.Game
{
    public interface IInventoryBridge
    {
        string ReadInventory(string playerId);
    }

    public static class InventoryRequest
    {
        // Stable platform IDs only; never names, paths or console commands.
        public static bool ValidPlayerId(string value) => value.Length <= 160 &&
            Regex.IsMatch(value, @"\A[A-Za-z0-9]+_[A-Za-z0-9_-]+\z");
    }
}
