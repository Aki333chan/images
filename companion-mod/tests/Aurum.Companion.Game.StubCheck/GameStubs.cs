// Заглушки игровых типов 7 Days to Die.
//
// НЕ ИГРА И НЕ ЧАСТЬ ПОСТАВКИ. Существуют, чтобы игровой слой мода вообще
// проходил через компилятор там, где сборок игры нет. Границы этой проверки
// описаны в README.md рядом.
//
// Все имена и сигнатуры переписаны с рабочего кода мода
// 7DaysToDie-ServerKit под ту же версию игры (V1.0+). Опечатки сохранены
// намеренно: KilledEntitiy именно так и называется в самой игре, и если
// «исправить» её здесь, проверка перестанет ловить настоящую ошибку.
//
// Всё живёт в глобальном пространстве имён — как и в самой игре.

using System.Collections.Generic;

#pragma warning disable CA1050 // типы в глобальном пространстве — так у игры

public interface IModApi
{
    void InitMod(Mod modInstance);
}

public class Mod
{
    public string Name = "";
    public string Path = "";
}

public enum EModEventResult
{
    Continue,
    StopHandlers,
    StopHandlersAndVanilla,
}

public enum EChatType { Global, Friends, Party, Whisper }

public enum EMessageSender { None, Server, Player }

public enum RespawnType
{
    NewGame,
    LoadedGame,
    EnterMultiplayer,
    JoinMultiplayer,
    Died,
    Teleport,
}

public enum EnumGamePrefs
{
    BloodMoonFrequency,
    MaxSpawnedZombies,
    MaxSpawnedAnimals,
    ServerMaxPlayerCount,
}

public static class GamePrefs
{
    public static int GetInt(EnumGamePrefs pref) => 0;
    public static string GetString(EnumGamePrefs pref) => "";
}

public abstract class PlatformUserIdentifierAbs
{
    public string CombinedString => "";
}

public class ClientInfo
{
    public int entityId;
    public string playerName = "";
    public string ip = "";
    public int ping;
    public PlatformUserIdentifierAbs? InternalId;
    public PlatformUserIdentifierAbs? PlatformId;
    public PlatformUserIdentifierAbs? CrossplatformId;

    public void SendPackage(NetPackage package) { }
}

public class ClientInfoCollection
{
    public List<ClientInfo> List => new List<ClientInfo>();
    public ClientInfo? ForEntityId(int entityId) => null;
    public ClientInfo? GetForPlayerName(string name) => null;
}

public class ConnectionManager
{
    public static ConnectionManager Instance = new ConnectionManager();
    public ClientInfoCollection Clients = new ClientInfoCollection();

    public void SendPackage(
        NetPackage package,
        bool onlyClientsAttachedToAnEntity = false,
        int attachedToEntityId = -1,
        int excludeEntityId = -1,
        int localOnlyEntityId = -1,
        object? filter = null,
        int distance = -1)
    { }
}

public abstract class NetPackage { }

public class NetPackageChat : NetPackage
{
    public NetPackageChat Setup(
        EChatType chatType,
        int senderEntityId,
        string message,
        string? mainName,
        EMessageSender sender,
        GeneratedTextManager.BbCodeSupportMode bbCode) => this;
}

public static class NetPackageManager
{
    public static T GetPackage<T>() where T : NetPackage, new() => new T();
}

public static class GeneratedTextManager
{
    public enum BbCodeSupportMode { NotSupported, Supported }
}

public class Vector3
{
    public float x, y, z;
}

public class Entity
{
    public int entityId;
    public Vector3 position = new Vector3();
    public bool IsAlive() => true;
}

public class EntityAlive : Entity
{
    public int Health;
}

public class EntityPlayer : EntityAlive
{
    public Progression? Progression;
}

public class Progression
{
    public int Level;
}

public class EntityEnemy : EntityAlive { }

public class EntityAnimal : EntityAlive { }

public class EntityList
{
    public List<Entity> list = new List<Entity>();
    public int Count => list.Count;
}

public class PlayerList
{
    public Dictionary<int, EntityPlayer> dict = new Dictionary<int, EntityPlayer>();
    public int Count => dict.Count;
}

public class BloodMoonComponent
{
    public bool BloodMoonActive;
}

public class AIDirector
{
    public BloodMoonComponent? BloodMoonComponent;
}

public class World
{
    public AIDirector? aiDirector;
    public PlayerList? Players;
    public EntityList? Entities;
    public ulong GetWorldTime() => 0;
}

public class FpsCounter
{
    public float Counter;
}

public class GameManager
{
    public static GameManager? Instance = new GameManager();
    public World? World = new World();
    public FpsCounter? fps = new FpsCounter();
}

public static class GameUtils
{
    public static int WorldTimeToDays(ulong worldTime) => 0;
    public static int WorldTimeToHours(ulong worldTime) => 0;
    public static int WorldTimeToMinutes(ulong worldTime) => 0;
}

public class VersionInformation
{
    public string LongString => "";
}

public static class Constants
{
    public static VersionInformation? cVersionInformation = new VersionInformation();
}

public static class Log
{
    public static void Out(string message) { }
    public static void Warning(string message) { }
    public static void Error(string message) { }
}

// ---------------------------------------------------------------- события
// Данные событий приходят по ссылке, а обработчики возвращают EModEventResult
// либо void — так этот API устроен начиная с V1.0. Прежние сигнатуры с
// позиционными аргументами устарели.

public struct SChatMessageData
{
    public ClientInfo? ClientInfo;
    public EChatType ChatType;
    public int SenderEntityId;
    public string? Message;
    public string? MainName;
}

public struct SPlayerSpawnedInWorldData
{
    public ClientInfo? ClientInfo;
    public RespawnType RespawnType;
    public Vector3i Position;
}

public struct SPlayerDisconnectedData
{
    public ClientInfo? ClientInfo;
    public bool Shutdown;
}

public struct SEntityKilledData
{
    /// <summary>Опечатка в имени — из самой игры. Не исправлять.</summary>
    public Entity? KilledEntitiy;
    public Entity? KillingEntity;
}

public struct SGameShutdownData { }

public struct Vector3i
{
    public int x, y, z;
}

public delegate EModEventResult ChatMessageHandler(ref SChatMessageData data);
public delegate void PlayerSpawnedInWorldHandler(ref SPlayerSpawnedInWorldData data);
public delegate void PlayerDisconnectedHandler(ref SPlayerDisconnectedData data);
public delegate void EntityKilledHandler(ref SEntityKilledData data);
public delegate void GameShutdownHandler(ref SGameShutdownData data);

public class ModEvent<THandler>
{
    public void RegisterHandler(THandler handler) { }
    public void UnregisterHandler(THandler handler) { }
}

public static class ModEvents
{
    public static ModEvent<ChatMessageHandler> ChatMessage = new ModEvent<ChatMessageHandler>();
    public static ModEvent<PlayerSpawnedInWorldHandler> PlayerSpawnedInWorld = new ModEvent<PlayerSpawnedInWorldHandler>();
    public static ModEvent<PlayerDisconnectedHandler> PlayerDisconnected = new ModEvent<PlayerDisconnectedHandler>();
    public static ModEvent<EntityKilledHandler> EntityKilled = new ModEvent<EntityKilledHandler>();
    public static ModEvent<GameShutdownHandler> GameShutdown = new ModEvent<GameShutdownHandler>();
}

#pragma warning restore CA1050
