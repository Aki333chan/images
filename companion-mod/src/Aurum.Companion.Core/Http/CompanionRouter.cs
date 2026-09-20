using System;
using System.Collections.Generic;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Json;

namespace Aurum.Companion.Core.Http
{
    /// <summary>
    /// Входящее направление: панель → мод.
    /// </summary>
    /// <remarks>
    /// Роутер отделён от прослушивания сокета намеренно: так вся логика
    /// маршрутов, проверок и ответов проверяется тестами без единого
    /// открытого порта, а на долю серверного класса остаётся только приём
    /// байтов.
    ///
    /// ПРО ПОТОКИ. Handle вызывается из потока HttpListener — то есть НЕ из
    /// главного потока игры. Всё, что трогает мир, идёт через IGameBridge,
    /// и переложить работу в главный поток — забота его реализации.
    /// </remarks>
    public sealed class CompanionRouter
    {
        /// <summary>Версия контракта. Панель по ней понимает, что умеет этот мод.</summary>
        public const string ContractVersion = "1";

        private readonly IGameBridge _game;
        private readonly string _token;
        private readonly string _modVersion;
        private readonly string _capabilitiesJson;
        private readonly string _language;
        private readonly ItemGrantGate _grants = new ItemGrantGate();

        public CompanionRouter(IGameBridge game, string token, string modVersion, CompanionConfig? config = null)
        {
            _game = game;
            _token = token;
            _modVersion = modVersion;
            _language = config?.Language ?? "en";
            var capabilities = new List<string> {
                "world-state", "online-players", "private-messages", "broadcast", "tickets", "reports", "localization",
                "event-ids", "blood-moon-schedule"
            };
            if (config?.ForwardChat ?? true) capabilities.Add("chat-events");
            if (config?.ForwardDeaths ?? true) capabilities.Add("death-events");
            if (game is IMapBridge) { capabilities.Add("map-read"); capabilities.Add("map-pois"); }
            if (game is IZoneBridge) capabilities.Add("zones-v2");
            if (game is IInventoryBridge) { capabilities.Add("inventory-read"); capabilities.Add("item-drop"); }
            if (game is ISavedInventoryBridge) { capabilities.Add("inventory-saved-read"); capabilities.Add("inventory-saved-reduce"); capabilities.Add("inventory-saved-replace"); }
            _capabilitiesJson = JsonWriter.Array(capabilities.ConvertAll(JsonWriter.String));
        }

        public bool IsAuthorized(string? header) => TokenAuth.Equals(_token, TokenAuth.Extract(header));

        public HttpResponseData Handle(HttpRequestData request, string? authorizationHeader)
        {
            if (!IsAuthorized(authorizationHeader))
            {
                // Ни намёка на то, чем именно не подошёл токен.
                return HttpResponseData.Error(401, "Неверный токен");
            }

            try
            {
                return Route(request);
            }
            catch (JsonReader.JsonException e)
            {
                return HttpResponseData.BadRequest("Некорректное тело запроса: " + e.Message);
            }
            catch (GameDispatchException e)
            {
                // Already-started game work cannot be undone by an HTTP timeout.
                return HttpResponseData.Error(e.TimedOut ? 504 : 503,
                    e.MayHaveExecuted ? "game_operation_outcome_unknown" : "game_operation_not_started");
            }
            catch (Exception e)
            {
                // Исключение из игрового слоя не должно ронять поток
                // прослушивания: мод обязан пережить любую отдельную команду.
                _game.LogError("Ошибка обработки запроса панели " + request.Method + " " + request.Path, e);
                return HttpResponseData.Error(500, "Внутренняя ошибка мода");
            }
        }

        private HttpResponseData Route(HttpRequestData request)
        {
            string[] parts = request.Segments;
            if (parts.Length == 1 && parts[0] == "zones" && _game is IZoneBridge zones)
            {
                try
                {
                    if (request.Method == "GET") return HttpResponseData.Ok(zones.ReadZones());
                    if (request.Method == "POST") return HttpResponseData.Ok(zones.SaveZones(ZoneRules.Read(request.Body)));
                }
                catch (InvalidOperationException e) when (e.Message.StartsWith("zones_", StringComparison.Ordinal) || e.Message == "world_loading")
                { return HttpResponseData.Error(e.Message == "zones_revision_conflict" ? 409 : 503, e.Message); }
            }
            if (_game is IInventoryBridge itemBridge)
            {
                if (request.Method == "GET" && parts.Length == 2 && parts[0] == "items")
                {
                    string query = Uri.UnescapeDataString(parts[1]).Trim();
                    if (query.Length < 2 || query.Length > 64) return HttpResponseData.BadRequest("invalid_item_search");
                    return HttpResponseData.Ok(JsonWriter.Object(new[] {
                        Field("sessionId", JsonWriter.String(_grants.SessionId)), Field("catalogue", itemBridge.SearchItems(query)) }));
                }
                if (request.Method == "POST" && parts.Length == 3 && parts[0] == "players" && parts[2] == "item-drop")
                {
                    var grant = ItemGrant.Read(Uri.UnescapeDataString(parts[1]), request.Body);
                    string status = _grants.Execute(grant, () => itemBridge.DropItem(grant));
                    return HttpResponseData.Ok(JsonWriter.Object(new[] {Field("status", JsonWriter.String(status)), Field("requestId", JsonWriter.String(grant.RequestId))}));
                }
            }
            if (request.Method == "POST" && parts.Length == 3 && parts[0] == "players" && parts[2] == "saved-stack" && _game is ISavedInventoryBridge editor)
            {
                var change = SavedStackChange.Read(Uri.UnescapeDataString(parts[1]), request.Body);
                return HttpResponseData.Ok(JsonWriter.Object(new[] {
                    Field("status", JsonWriter.String(editor.EditSavedStack(change))),
                    Field("requestId", JsonWriter.String(change.RequestId)) }));
            }
            if (request.Method == "GET" && _game is ISavedInventoryBridge saved)
            {
                if (parts.Length == 3 && parts[0] == "saved-players")
                {
                    string query = Uri.UnescapeDataString(parts[1]);
                    if (query == "_") query = "";
                    if (query.Length > 80 || !int.TryParse(parts[2], out int offset) || offset < 0 || offset > 10000)
                        return HttpResponseData.BadRequest("invalid_saved_players_query");
                    return HttpResponseData.Ok(saved.SavedPlayers(query, offset));
                }
                if (parts.Length == 3 && parts[0] == "players" && parts[2] == "saved-inventory")
                {
                    string id = Uri.UnescapeDataString(parts[1]);
                    if (!InventoryRequest.ValidPlayerId(id)) return HttpResponseData.BadRequest("invalid_player_id");
                    return HttpResponseData.Ok(saved.ReadSavedInventory(id));
                }
            }
            if (request.Method == "GET" && parts.Length == 3 && parts[0] == "players" && parts[2] == "inventory" && _game is IInventoryBridge inventory)
            {
                string id = Uri.UnescapeDataString(parts[1]);
                if (!InventoryRequest.ValidPlayerId(id)) return HttpResponseData.BadRequest("invalid_player_id");
                return HttpResponseData.Ok(inventory.ReadInventory(id));
            }
            if (request.Method == "GET" && parts.Length >= 2 && parts[0] == "map" && _game is IMapBridge map)
            {
                if (parts.Length == 2 && parts[1] == "info") return HttpResponseData.Ok(map.ReadMapInfo());
                if (parts.Length == 2 && parts[1] == "markers") return HttpResponseData.Ok(map.ReadMapMarkers());
                if (parts.Length == 2 && parts[1] == "pois") return HttpResponseData.Ok(map.ReadMapPois());
                if (parts.Length == 5 && parts[1] == "tile")
                {
                    if (!int.TryParse(parts[2], out int zoom) || !int.TryParse(parts[3], out int x) || !int.TryParse(parts[4], out int z) || !NativeMapFiles.ValidCoordinates(zoom, x, z)) return HttpResponseData.BadRequest("invalid_tile");
                    return HttpResponseData.Ok(map.ReadMapTile(zoom, x, z));
                }
            }

            // GET /ping — панель проверяет, что мод жив и какой он версии.
            if (request.Method == "GET" && parts.Length == 1 && parts[0] == "ping")
            {
                return HttpResponseData.Ok(JsonWriter.Object(new[]
                {
                    Field("ok", JsonWriter.Bool(true)),
                    Field("mod", JsonWriter.String("aurum-companion")),
                    Field("version", JsonWriter.String(_modVersion)),
                    Field("contract", JsonWriter.String(ContractVersion)),
                    Field("capabilities", _capabilitiesJson),
                    Field("language", JsonWriter.String(_language)),
                }));
            }

            // GET /state — состояние мира, прочитанное у игры.
            if (request.Method == "GET" && parts.Length == 1 && parts[0] == "state")
            {
                return HttpResponseData.Ok(WorldStateJson(_game.ReadWorldState()));
            }

            // GET /players — кто в сети.
            if (request.Method == "GET" && parts.Length == 1 && parts[0] == "players")
            {
                var items = new List<string>();
                foreach (var player in _game.OnlinePlayers()) items.Add(PlayerJson(player));
                return HttpResponseData.Ok(JsonWriter.Object(new[] { Field("players", JsonWriter.Array(items)) }));
            }

            // POST /players/{id}/message — ответ модератора игроку в чат.
            if (request.Method == "POST" && parts.Length == 3 && parts[0] == "players" && parts[2] == "message")
            {
                return SendMessage(Uri.UnescapeDataString(parts[1]), request.Body);
            }

            // POST /broadcast — объявление всем.
            if (request.Method == "POST" && parts.Length == 1 && parts[0] == "broadcast")
            {
                string text = ReadText(request.Body);
                if (text.Length == 0) return HttpResponseData.BadRequest("Пустое сообщение");
                _game.Broadcast(text);
                return HttpResponseData.Ok(JsonWriter.Object(new[] { Field("ok", JsonWriter.Bool(true)) }));
            }

            return HttpResponseData.NotFound("Такого метода у мода нет");
        }

        private HttpResponseData SendMessage(string playerId, string body)
        {
            string text = ReadText(body);
            if (text.Length == 0) return HttpResponseData.BadRequest("Пустое сообщение");

            bool delivered = _game.SendPrivateMessage(playerId, text);

            // Игрок офлайн — это НЕ ошибка запроса, а обычное дело: модератор
            // отвечает на тикет, когда ему удобно, а не когда игрок в сети.
            // Панель должна отличить «не доставлено» от «запрос неверный»,
            // поэтому 200 с флагом, а не 404.
            return HttpResponseData.Ok(JsonWriter.Object(new[]
            {
                Field("ok", JsonWriter.Bool(true)),
                Field("delivered", JsonWriter.Bool(delivered)),
            }));
        }

        private static string ReadText(string body)
        {
            if (string.IsNullOrWhiteSpace(body)) return "";
            var map = JsonReader.ParseObject(body);
            string text = (JsonReader.StringOrNull(map, "text") ?? "").Trim();

            // Перевод строки внутри сообщения умеет подделывать чужую строку
            // журнала сервера, а в игровом чате всё равно не отображается.
            var sb = new System.Text.StringBuilder(text.Length);
            foreach (char c in text) sb.Append(char.IsControl(c) ? ' ' : c);
            return sb.ToString().Trim();
        }

        private static string PlayerJson(OnlinePlayer p) => JsonWriter.Object(new[]
        {
            Field("entityId", JsonWriter.Number(p.EntityId)),
            Field("playerId", JsonWriter.String(p.PlayerId)),
            Field("crossId", JsonWriter.String(p.CrossId)),
            Field("name", JsonWriter.String(p.Name)),
            Field("level", JsonWriter.Number(p.Level)),
            Field("health", JsonWriter.Number(p.Health)),
            Field("ping", JsonWriter.Number(p.Ping)),
            Field("x", JsonWriter.Coordinate(p.X)),
            Field("y", JsonWriter.Coordinate(p.Y)),
            Field("z", JsonWriter.Coordinate(p.Z)),
        });

        private static string WorldStateJson(WorldState s) => JsonWriter.Object(new[]
        {
            Field("ready", JsonWriter.Bool(s.Ready)),
            Field("day", JsonWriter.Number(s.Day)),
            Field("hour", JsonWriter.Number(s.Hour)),
            Field("minute", JsonWriter.Number(s.Minute)),
            // Не «день кратен семи», а факт от самой игры.
            Field("bloodMoonActive", JsonWriter.Bool(s.IsBloodMoonActive)),
            Field("bloodMoonFrequency", s.BloodMoonFrequency < 0 ? "null" : JsonWriter.Number(s.BloodMoonFrequency)),
            Field("bloodMoonRange", s.BloodMoonRange < 0 ? "null" : JsonWriter.Number(s.BloodMoonRange)),
            Field("bloodMoonNextDay", s.BloodMoonNextDay <= 0 ? "null" : JsonWriter.Number(s.BloodMoonNextDay)),
            Field("fps", JsonWriter.Coordinate(s.Fps)),
            Field("zombies", JsonWriter.Number(s.Zombies)),
            Field("maxZombies", JsonWriter.Number(s.MaxZombies)),
            Field("animals", JsonWriter.Number(s.Animals)),
            Field("onlinePlayers", JsonWriter.Number(s.OnlinePlayers)),
            Field("maxPlayers", JsonWriter.Number(s.MaxPlayers)),
            Field("version", JsonWriter.String(s.Version)),
        });

        private static KeyValuePair<string, string> Field(string key, string value) =>
            new KeyValuePair<string, string>(key, value);
    }
}
