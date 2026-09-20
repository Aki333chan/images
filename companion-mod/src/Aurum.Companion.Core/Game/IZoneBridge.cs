namespace Aurum.Companion.Core.Game
{
    public interface IZoneBridge
    {
        string ReadZones();
        string SaveZones(ZoneRules rules);
    }
}
