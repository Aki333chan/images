using System;
using System.Collections.Generic;
using Aurum.Companion.Core.Game;

namespace Aurum.Companion.Core
{
    /// <summary>
    /// Единственная точка касания игры.
    /// </summary>
    /// <remarks>
    /// Весь остальной код ядра не знает про 7 Days to Die вообще ничего и
    /// потому собирается и проверяется тестами на любой машине. Реализация
    /// живёт в проекте Game, который без Assembly-CSharp.dll с игрового
    /// сервера не собрать.
    ///
    /// Отсюда правило: в этом интерфейсе не должно появиться ни одного типа
    /// из игры. Как только появится — тесты ядра станет невозможно собрать,
    /// и разделение потеряет смысл.
    ///
    /// ПРО ПОТОКИ. Всё, что здесь объявлено, вызывается ядром из его
    /// собственных потоков — то есть НЕ из главного потока игры. Реализация
    /// обязана сама переложить работу с миром в главный поток: обращение к
    /// Unity-объектам из чужого потока роняет сервер.
    /// </remarks>
    public interface IGameBridge
    {
        /// <summary>
        /// Личное сообщение игроку. false — игрока нет в сети.
        /// </summary>
        /// <remarks>
        /// Ради этого метода мод нужен и сам по себе: у ванильной консоли
        /// 7 Days to Die нет команды «написать одному игроку», есть только
        /// say на весь сервер. Ответ модератора на тикет иначе пришлось бы
        /// зачитывать вслух всему серверу.
        /// </remarks>
        bool SendPrivateMessage(string playerId, string text);

        /// <summary>Сообщение всем в игровой чат.</summary>
        void Broadcast(string text);

        /// <summary>Кто сейчас в сети.</summary>
        IReadOnlyList<OnlinePlayer> OnlinePlayers();

        /// <summary>Найти игрока в сети по идентификатору платформы или нику.</summary>
        OnlinePlayer? FindPlayer(string idOrName);

        /// <summary>Состояние мира, прочитанное у игры.</summary>
        WorldState ReadWorldState();

        void Log(string message);

        void LogError(string message, Exception? error);
    }
}
