using System;
using System.IO;
using Aurum.Companion.Core;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Http;
using Aurum.Companion.Core.Panel;
using Aurum.Companion.Core.Tickets;

namespace Aurum.Companion.Game
{
    /// <summary>
    /// Точка входа мода.
    /// </summary>
    /// <remarks>
    /// Мод загружается штатным механизмом самой игры: класс, реализующий
    /// IModApi, находит ModManager при старте сервера. Harmony здесь НЕ нужен
    /// и не используется — все события берутся из публичного ModEvents,
    /// который поддерживает сама TFP. Это осознанный выбор: патчи чужих
    /// методов ломаются на каждом обновлении игры, а объявленный API — нет.
    ///
    /// Мод СЕРВЕРНЫЙ. Игрокам ставить ничего не нужно, клиент о нём не знает.
    /// </remarks>
    public sealed class AurumCompanionMod : IModApi
    {
        private const string Version = "1.0.0";
        private const string ConfigFileName = "companion.cfg";

        private static CompanionConfig? _config;
        private static SdtdGameBridge? _game;
        private static EventQueue? _queue;
        private static EventSender? _sender;
        private static TicketService? _tickets;
        private static TicketCooldown? _cooldown;
        private static CompanionHttpServer? _http;
        private static bool _forwardChat;
        private static bool _forwardDeaths;

        public void InitMod(Mod modInstance)
        {
            try
            {
                // Здесь нас вызывает сама игра, то есть мы уже в главном
                // потоке — единственный момент, когда его контекст можно взять.
                MainThread.Capture();

                _game = new SdtdGameBridge();
                _config = LoadConfig(modInstance);

                var problems = _config.Problems();
                if (problems.Count > 0)
                {
                    // Не бросаем: упавший мод в 7 Days to Die утаскивает за
                    // собой запуск сервера. Лучше сказать в журнал и не мешать.
                    foreach (string problem in problems)
                    {
                        Log.Warning("[AurumCompanion] Настройка: " + problem);
                    }
                    Log.Warning("[AurumCompanion] Мод загружен, но не настроен — связи с панелью не будет.");
                    return;
                }

                _forwardChat = _config.ForwardChat;
                _forwardDeaths = _config.ForwardDeaths;
                _queue = new EventQueue(_config.EventQueueLimit);
                _cooldown = new TicketCooldown(_config.TicketCooldownSeconds);

                var panel = new PanelClient(_config, new HttpClientTransport());
                _tickets = new TicketService(panel, _game, _cooldown);
                _sender = new EventSender(_queue, panel, _game);
                _sender.Start();

                _http = new CompanionHttpServer(
                    new CompanionRouter(_game, _config.Token, Version),
                    _game,
                    _config.ListenHost,
                    _config.ListenPort);
                _http.Start();

                RegisterHandlers();
                Log.Out("[AurumCompanion] " + Version + " готов.");
            }
            catch (Exception e)
            {
                // Никакая наша ошибка не должна помешать серверу запуститься:
                // без мода люди играют, без сервера — нет.
                Log.Error("[AurumCompanion] Не удалось запуститься: " + e);
            }
        }

        /// <summary>
        /// Читает настройки рядом с модом.
        /// </summary>
        /// <remarks>
        /// Файл лежит в папке мода, а не в общих настройках сервера: в нём
        /// токен, и обновление мода не должно его затирать — поэтому в
        /// поставке лежит только companion.cfg.example.
        /// </remarks>
        private static CompanionConfig LoadConfig(Mod modInstance)
        {
            string path = Path.Combine(modInstance.Path, ConfigFileName);
            if (!File.Exists(path))
            {
                Log.Warning("[AurumCompanion] Нет " + ConfigFileName + " — скопируйте companion.cfg.example и заполните.");
                return CompanionConfig.Parse(new string[0]);
            }
            // Содержимое не логируем ни при каких обстоятельствах: там токен.
            return CompanionConfig.Load(path);
        }

        private static void RegisterHandlers()
        {
            ModEvents.ChatMessage.RegisterHandler(OnChatMessage);
            ModEvents.PlayerSpawnedInWorld.RegisterHandler(OnPlayerSpawnedInWorld);
            ModEvents.PlayerDisconnected.RegisterHandler(OnPlayerDisconnected);
            ModEvents.EntityKilled.RegisterHandler(OnEntityKilled);
            ModEvents.GameShutdown.RegisterHandler(OnGameShutdown);
        }

        /// <summary>
        /// Сообщение игрового чата.
        /// </summary>
        /// <remarks>
        /// Здесь главный поток сервера, и потратить его нельзя ни на что.
        /// Поэтому: разобрать строку (мгновенно), либо отдать её TicketService,
        /// который сам уйдёт в другой поток, либо положить в очередь.
        ///
        /// Возврат StopHandlersAndVanilla глотает сообщение. Для /report это
        /// не удобство, а требование: жалобу не должен увидеть тот, на кого
        /// жалуются.
        /// </remarks>
        private static EModEventResult OnChatMessage(ref SChatMessageData data)
        {
            try
            {
                ClientInfo? client = data.ClientInfo;
                // Сообщения самого сервера и консоли игроку не принадлежат.
                if (client?.InternalId == null) return EModEventResult.Continue;

                string message = data.Message ?? "";
                ChatCommand command = ChatCommand.Parse(message);

                if (command.Kind != ChatCommandKind.None)
                {
                    OnlinePlayer? player = SdtdGameBridge.Describe(client);
                    if (player == null) return EModEventResult.Continue;
                    return _tickets != null && _tickets.Handle(player, command)
                        ? EModEventResult.StopHandlersAndVanilla
                        : EModEventResult.Continue;
                }

                if (_forwardChat && _queue != null)
                {
                    var player = SdtdGameBridge.Describe(client);
                    if (player != null)
                    {
                        _queue.Enqueue(new GameEvent(GameEventKind.Chat, player.PlayerId, player.Name)
                        {
                            Text = message,
                            X = player.X,
                            Y = player.Y,
                            Z = player.Z,
                        });
                    }
                }
            }
            catch (Exception e)
            {
                // Исключение отсюда оборвало бы обработку чата для всех модов.
                Log.Error("[AurumCompanion] Ошибка обработки чата: " + e);
            }
            return EModEventResult.Continue;
        }

        /// <summary>
        /// Игрок появился в мире.
        /// </summary>
        /// <remarks>
        /// Именно это событие, а не PlayerLogin: при входе клиент есть, а тела
        /// ещё нет, и координаты с уровнем взять неоткуда.
        /// </remarks>
        private static void OnPlayerSpawnedInWorld(ref SPlayerSpawnedInWorldData data)
        {
            try
            {
                // Событие приходит и при возрождении, и при телепорте —
                // «зашёл на сервер» из него не следует. Нас интересует только
                // первый вход, остальное было бы шумом в панели.
                if (data.RespawnType != RespawnType.EnterMultiplayer
                    && data.RespawnType != RespawnType.JoinMultiplayer)
                {
                    return;
                }

                OnlinePlayer? player = SdtdGameBridge.Describe(data.ClientInfo);
                if (player == null || _queue == null) return;

                _queue.Enqueue(new GameEvent(GameEventKind.Join, player.PlayerId, player.Name)
                {
                    X = player.X, Y = player.Y, Z = player.Z,
                });
            }
            catch (Exception e)
            {
                Log.Error("[AurumCompanion] Ошибка обработки входа: " + e);
            }
        }

        private static void OnPlayerDisconnected(ref SPlayerDisconnectedData data)
        {
            try
            {
                OnlinePlayer? player = SdtdGameBridge.Describe(data.ClientInfo);
                if (player == null) return;

                // Ожидание обращений забывается вместе с игроком: держать его
                // для вышедшего незачем, а карта иначе росла бы вечно.
                _cooldown?.Forget(player.PlayerId);

                _queue?.Enqueue(new GameEvent(GameEventKind.Leave, player.PlayerId, player.Name));
            }
            catch (Exception e)
            {
                Log.Error("[AurumCompanion] Ошибка обработки выхода: " + e);
            }
        }

        /// <summary>
        /// Кто-то погиб.
        /// </summary>
        /// <remarks>
        /// Событие срабатывает на КАЖДОГО убитого зомби — то есть тысячи раз
        /// за ночь орды. Поэтому первым делом проверяется, что погиб игрок, и
        /// только потом делается что-либо ещё: иначе мод стал бы заметен в
        /// нагрузке ровно тогда, когда серверу тяжелее всего.
        /// </remarks>
        private static void OnEntityKilled(ref SEntityKilledData data)
        {
            try
            {
                if (!_forwardDeaths || _queue == null) return;
                // Имя поля с опечаткой — так оно называется в самой игре.
                if (!(data.KilledEntitiy is EntityPlayer victim)) return;

                ClientInfo? victimClient = ConnectionManager.Instance.Clients.ForEntityId(victim.entityId);
                OnlinePlayer? victimPlayer = SdtdGameBridge.Describe(victimClient);
                if (victimPlayer == null) return;

                var killerPlayer = data.KillingEntity as EntityPlayer;
                bool pvp = killerPlayer != null && killerPlayer.entityId != victim.entityId;

                var e = new GameEvent(
                    pvp ? GameEventKind.PlayerKill : GameEventKind.Death,
                    victimPlayer.PlayerId,
                    victimPlayer.Name)
                {
                    X = victim.position.x,
                    Y = victim.position.y,
                    Z = victim.position.z,
                };

                if (pvp)
                {
                    ClientInfo? killerClient = ConnectionManager.Instance.Clients.ForEntityId(killerPlayer!.entityId);
                    OnlinePlayer? killer = SdtdGameBridge.Describe(killerClient);
                    e.ActorId = killer?.PlayerId;
                    e.ActorName = killer?.Name;
                }

                _queue.Enqueue(e);
            }
            catch (Exception ex)
            {
                Log.Error("[AurumCompanion] Ошибка обработки смерти: " + ex);
            }
        }

        /// <summary>
        /// Сервер выключается.
        /// </summary>
        /// <remarks>
        /// Останавливаемся ограниченно по времени: у мода нет права задерживать
        /// выключение сервера из-за неотвеченной панели. Несколько последних
        /// событий при этом можно потерять — это дешевле, чем зависшее
        /// выключение.
        /// </remarks>
        private static void OnGameShutdown(ref SGameShutdownData data)
        {
            try
            {
                _http?.Stop();
                _sender?.Stop();
                Log.Out("[AurumCompanion] Остановлен.");
            }
            catch (Exception e)
            {
                Log.Error("[AurumCompanion] Ошибка остановки: " + e);
            }
        }
    }
}
