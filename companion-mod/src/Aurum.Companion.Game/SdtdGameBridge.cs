using System;
using System.Collections.Generic;
using Aurum.Companion.Core;
using Aurum.Companion.Core.Game;

namespace Aurum.Companion.Game
{
    /// <summary>
    /// Реализация моста к 7 Days to Die.
    /// </summary>
    /// <remarks>
    /// Единственный класс мода, который знает игровые типы. Логики здесь
    /// нарочно нет — только перевод игровых объектов в свои и обратно, — и
    /// это плата за то, что всё остальное можно проверить тестами.
    ///
    /// Каждый публичный метод вызывается из чужого потока и потому уходит в
    /// главный через <see cref="MainThread"/>. Забыть об этом здесь — значит
    /// уронить сервер игроку.
    /// </remarks>
    internal sealed class SdtdGameBridge : IGameBridge
    {
        /// <summary>Имя, от которого мод пишет в чат.</summary>
        private const string SenderName = "Панель";

        public bool SendPrivateMessage(string playerId, string text)
        {
            return MainThread.Get(() =>
            {
                ClientInfo? client = FindClient(playerId);
                if (client == null) return false;

                // Whisper: сообщение видит только адресат. Для ответа на
                // жалобу это не удобство, а требование — иначе разбирательство
                // прочитал бы весь сервер.
                client.SendPackage(NetPackageManager.GetPackage<NetPackageChat>()
                    .Setup(EChatType.Whisper, -1, text, SenderName, EMessageSender.None,
                           GeneratedTextManager.BbCodeSupportMode.Supported));
                return true;
            }, false);
        }

        public void Broadcast(string text)
        {
            MainThread.Post(() =>
                ConnectionManager.Instance.SendPackage(
                    NetPackageManager.GetPackage<NetPackageChat>()
                        .Setup(EChatType.Global, -1, text, SenderName, EMessageSender.None,
                               GeneratedTextManager.BbCodeSupportMode.Supported),
                    true, -1, -1, -1, null, 192));
        }

        public IReadOnlyList<OnlinePlayer> OnlinePlayers()
        {
            return MainThread.Get<IReadOnlyList<OnlinePlayer>>(() =>
            {
                var result = new List<OnlinePlayer>();
                foreach (ClientInfo client in ConnectionManager.Instance.Clients.List)
                {
                    OnlinePlayer? player = Describe(client);
                    if (player != null) result.Add(player);
                }
                return result;
            }, new List<OnlinePlayer>());
        }

        public OnlinePlayer? FindPlayer(string idOrName)
        {
            return MainThread.Get<OnlinePlayer?>(() =>
            {
                ClientInfo? client = FindClient(idOrName);
                return client == null ? null : Describe(client);
            }, null);
        }

        public WorldState ReadWorldState()
        {
            return MainThread.Get(() =>
            {
                var state = new WorldState();
                // До загрузки мира менеджера ещё нет — панель в этот момент
                // должна получить пустое состояние, а не исключение.
                GameManager? manager = GameManager.Instance;
                World? world = manager?.World;
                if (manager == null || world == null) return state;

                ulong worldTime = world.GetWorldTime();
                state.Day = GameUtils.WorldTimeToDays(worldTime);
                state.Hour = GameUtils.WorldTimeToHours(worldTime);
                state.Minute = GameUtils.WorldTimeToMinutes(worldTime);

                // Ради этой строки мод панели и нужен: без него она вынуждена
                // считать «день кратен семи», хотя частота настраивается, а
                // сама орда может быть отключена.
                state.IsBloodMoonActive = world.aiDirector?.BloodMoonComponent?.BloodMoonActive ?? false;
                state.BloodMoonFrequency = ReadIntPref(EnumGamePrefs.BloodMoonFrequency, -1);

                state.Fps = manager.fps?.Counter ?? 0f;
                state.MaxZombies = ReadIntPref(EnumGamePrefs.MaxSpawnedZombies, 0);
                state.MaxPlayers = ReadIntPref(EnumGamePrefs.ServerMaxPlayerCount, 0);
                state.OnlinePlayers = world.Players?.Count ?? 0;
                state.Version = Constants.cVersionInformation?.LongString;

                CountEntities(world, state);
                return state;
            }, new WorldState());
        }

        /// <summary>
        /// Живые зомби и животные.
        /// </summary>
        /// <remarks>
        /// Считается перебором сущностей — заметно дороже остальных полей, но
        /// перебор идёт в главном потоке один раз на запрос состояния, а
        /// панель спрашивает его редко. Дешёвого способа игра не даёт.
        /// </remarks>
        private static void CountEntities(World world, WorldState state)
        {
            var entities = world.Entities?.list;
            if (entities == null) return;

            foreach (Entity entity in entities)
            {
                if (entity == null || !entity.IsAlive()) continue;
                if (entity is EntityEnemy) state.Zombies++;
                else if (entity is EntityAnimal) state.Animals++;
            }
        }

        /// <summary>
        /// Значение настройки сервера.
        /// </summary>
        /// <remarks>
        /// В try намеренно: набор EnumGamePrefs меняется от версии к версии, и
        /// исчезнувшая настройка не должна лишать панель всего состояния мира.
        /// Значение по умолчанию честно означает «сервер не сказал».
        /// </remarks>
        private static int ReadIntPref(EnumGamePrefs pref, int fallback)
        {
            try
            {
                return GamePrefs.GetInt(pref);
            }
            catch (Exception)
            {
                return fallback;
            }
        }

        /// <summary>
        /// Ищет игрока в сети по идентификатору платформы, кроссплатформенному
        /// идентификатору или нику.
        /// </summary>
        /// <remarks>
        /// Три способа, потому что панель адресует по идентификатору, а человек
        /// в чате — по нику, и оба должны работать.
        /// </remarks>
        private static ClientInfo? FindClient(string idOrName)
        {
            if (string.IsNullOrWhiteSpace(idOrName)) return null;
            string needle = idOrName.Trim();

            foreach (ClientInfo client in ConnectionManager.Instance.Clients.List)
            {
                if (client == null) continue;
                if (string.Equals(client.InternalId?.CombinedString, needle, StringComparison.Ordinal)) return client;
                if (string.Equals(client.PlatformId?.CombinedString, needle, StringComparison.Ordinal)) return client;
            }

            // Ник — в последнюю очередь: он не уникален и меняется, а
            // идентификатор нет. Совпадение по нику не должно перебивать
            // точное совпадение по идентификатору.
            foreach (ClientInfo client in ConnectionManager.Instance.Clients.List)
            {
                if (client != null && string.Equals(client.playerName, needle, StringComparison.OrdinalIgnoreCase))
                {
                    return client;
                }
            }
            return null;
        }

        /// <summary>Переводит клиента игры в модель ядра. null — игрок ещё не в мире.</summary>
        internal static OnlinePlayer? Describe(ClientInfo? client)
        {
            if (client?.InternalId == null) return null;

            var player = new OnlinePlayer(client.entityId, client.InternalId.CombinedString, client.playerName ?? "")
            {
                CrossId = client.CrossplatformId?.CombinedString,
                Ping = client.ping,
            };

            // Сущность появляется не сразу после подключения: между входом и
            // спавном в мире игрок уже клиент, но ещё не тело.
            EntityPlayer? entity = GameManager.Instance?.World?.Players?.dict != null
                && GameManager.Instance.World.Players.dict.TryGetValue(client.entityId, out EntityPlayer found)
                ? found
                : null;

            if (entity != null)
            {
                player.Health = entity.Health;
                player.Level = entity.Progression?.Level ?? 0;
                player.X = entity.position.x;
                player.Y = entity.position.y;
                player.Z = entity.position.z;
            }
            return player;
        }

        public void Log(string message) => global::Log.Out("[AurumCompanion] " + message);

        public void LogError(string message, Exception? error) =>
            global::Log.Error("[AurumCompanion] " + message + (error == null ? "" : ": " + error));
    }
}
