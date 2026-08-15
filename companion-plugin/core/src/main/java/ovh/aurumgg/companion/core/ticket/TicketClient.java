package ovh.aurumgg.companion.core.ticket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import ovh.aurumgg.companion.core.CompanionConfig;
import ovh.aurumgg.companion.core.json.Json;
import ovh.aurumgg.companion.core.json.JsonParser;

/**
 * Исходящее направление: плагин → панель.
 *
 * Обращается к internal-эндпоинту панели по приватному адресу (10.0.0.1),
 * авторизуясь тем же токеном сервера. Публичный домен здесь не используется —
 * трафик не должен покидать туннель.
 *
 * Метод send() блокирующий и обязан вызываться из асинхронного потока:
 * в основном потоке сервера сетевые вызовы недопустимы.
 */
public final class TicketClient {

    /** Ответ панели: создан новый тикет или сообщение добавлено к существующему. */
    public record Result(boolean created, String ticketId) {}

    public static final class TicketException extends Exception {
        public TicketException(String message) {
            super(message);
        }
    }

    private final CompanionConfig config;
    private final HttpClient http;

    public TicketClient(CompanionConfig config) {
        this(
                config,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        // Панель во внутренней сети: прокси и редиректы не нужны.
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    TicketClient(CompanionConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    public Result send(UUID playerUuid, String playerName, String text) throws TicketException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("playerUuid", Json.string(playerUuid.toString()));
        body.put("playerName", Json.string(playerName));
        body.put("text", Json.string(text));

        String base = config.panelBaseUrl().endsWith("/")
                ? config.panelBaseUrl().substring(0, config.panelBaseUrl().length() - 1)
                : config.panelBaseUrl();
        URI uri = URI.create(base + "/api/internal/minecraft/servers/" + config.panelServerId() + "/tickets");

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + config.token())
                .POST(HttpRequest.BodyPublishers.ofString(Json.object(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // В сообщение не попадает ни токен, ни адрес панели.
            throw new TicketException("панель недоступна");
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new TicketException("панель отвергла токен сервера");
        }
        if (response.statusCode() >= 400) {
            throw new TicketException("панель ответила " + response.statusCode());
        }

        try {
            Map<String, Object> parsed = JsonParser.parseObject(response.body());
            boolean created = Boolean.TRUE.equals(parsed.get("created"));
            Object ticketId = parsed.get("ticketId");
            return new Result(created, ticketId == null ? null : ticketId.toString());
        } catch (RuntimeException e) {
            throw new TicketException("непонятный ответ панели");
        }
    }
}
