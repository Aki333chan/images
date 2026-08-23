using System;

namespace Aurum.Companion.Core.Game
{
    /// <summary>
    /// Игрок в сети — то, что мод знает о нём без разбора текста.
    /// </summary>
    /// <remarks>
    /// Идентификаторов два, и путать их нельзя. <see cref="PlayerId"/> — это
    /// идентификатор платформы (Steam_7656…), он переживает выход игрока и им
    /// адресуются команды. <see cref="EntityId"/> живёт только пока игрок в
    /// сети и меняется при перезаходе.
    /// </remarks>
    public sealed class OnlinePlayer
    {
        public OnlinePlayer(int entityId, string playerId, string name)
        {
            EntityId = entityId;
            PlayerId = playerId;
            Name = name;
        }

        public int EntityId { get; }
        public string PlayerId { get; }
        public string Name { get; }
        public string? CrossId { get; set; }
        public int Level { get; set; }
        public int Health { get; set; }
        public int Ping { get; set; }
        public float X { get; set; }
        public float Y { get; set; }
        public float Z { get; set; }
    }

    /// <summary>
    /// Состояние мира, прочитанное у самой игры, а не выведенное из текста.
    /// </summary>
    /// <remarks>
    /// Ради двух полей здесь всё и затевалось.
    ///
    /// <see cref="IsBloodMoonActive"/> — панель без мода вынуждена считать
    /// «день кратен семи», хотя частота орды настраивается (BloodMoonFrequency),
    /// а сама ночь может быть отключена. Здесь это факт, а не допущение.
    ///
    /// <see cref="Fps"/> — единственный показатель здоровья сервера, который
    /// у 7 Days to Die вообще есть: тика фиксированной частоты, как в
    /// Minecraft, здесь нет, и «TPS» рисовать неоткуда.
    /// </remarks>
    public sealed class WorldState
    {
        public int Day { get; set; }
        public int Hour { get; set; }
        public int Minute { get; set; }
        public bool IsBloodMoonActive { get; set; }

        /// <summary>Через сколько дней орда. -1 — сервер не сказал.</summary>
        public int BloodMoonFrequency { get; set; } = -1;

        public float Fps { get; set; }
        public int Zombies { get; set; }
        public int MaxZombies { get; set; }
        public int Animals { get; set; }
        public int OnlinePlayers { get; set; }
        public int MaxPlayers { get; set; }
        public string? Version { get; set; }
    }

    /// <summary>Что случилось в игре. Уходит в панель как есть.</summary>
    public enum GameEventKind
    {
        Chat,
        Join,
        Leave,
        Death,
        PlayerKill,
    }

    /// <summary>
    /// Событие игры.
    /// </summary>
    /// <remarks>
    /// Координаты здесь не для карты, а для разбора жалоб: «убил на PvE» и
    /// «снёс базу» проверяются именно по месту и времени.
    /// </remarks>
    public sealed class GameEvent
    {
        public GameEvent(GameEventKind kind, string playerId, string playerName)
        {
            Kind = kind;
            PlayerId = playerId;
            PlayerName = playerName;
            OccurredAt = DateTimeOffset.UtcNow;
        }

        public GameEventKind Kind { get; }
        public string PlayerId { get; }
        public string PlayerName { get; }
        public DateTimeOffset OccurredAt { get; set; }

        /// <summary>Текст сообщения — только для событий чата.</summary>
        public string? Text { get; set; }

        /// <summary>Кто убил, для PlayerKill: жертва в PlayerId, убийца здесь.</summary>
        public string? ActorId { get; set; }
        public string? ActorName { get; set; }

        public float X { get; set; }
        public float Y { get; set; }
        public float Z { get; set; }
    }
}
