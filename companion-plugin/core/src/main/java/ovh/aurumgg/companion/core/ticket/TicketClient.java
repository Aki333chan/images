package ovh.aurumgg.companion.core.ticket;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
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
                        // ЯВНО HTTP/1.1, И ЭТО НЕ ПЕРЕСТРАХОВКА.
                        //
                        // По умолчанию HttpClient просит HTTP/2, а по обычному
                        // http:// это делается апгрейдом: к запросу добавляются
                        // заголовки Connection: Upgrade и Upgrade: h2c.
                        //
                        // Панель — Node, и к её HTTP-серверу прицеплен socket.io
                        // (консоль сервера работает через него). Как только у
                        // Node-сервера появляется обработчик события upgrade,
                        // ВСЕ запросы с этим заголовком уходят в него мимо
                        // обычного конвейера. socket.io видит чужой путь и
                        // просто рвёт соединение, не ответив ничего.
                        //
                        // Снаружи это выглядело так: curl и wget до панели
                        // доходят (они шлют обычный HTTP/1.1), а плагин
                        // получает пустой ответ и голый IOException. Проверять
                        // после этого начинают сеть, в которой всё цело.
                        //
                        // Панель отвечает только по HTTP/1.1, так что терять
                        // тут нечего: HTTP/2 у неё нет ни в каком виде.
                        .version(HttpClient.Version.HTTP_1_1)
                        .build());
    }

    TicketClient(CompanionConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    /**
     * Адрес эндпоинта панели.
     *
     * Лишний слэш в конце base-url — частая опечатка, поэтому он срезается.
     * А вот адрес, из которого URI вообще не собирается (пробел, кириллица,
     * забытая схема), раньше ронял URI.create необработанным
     * IllegalArgumentException прямо в асинхронной задаче — игрок видел
     * «попробуй позже», а в консоль падал стектрейс без единого намёка на
     * config.yml.
     */
    URI endpoint(String path) throws TicketException {
        String raw = config.panelBaseUrl();
        String base = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        try {
            URI uri = URI.create(base + path);
            if (uri.getHost() == null) throw new IllegalArgumentException("нет хоста");
            return uri;
        } catch (IllegalArgumentException e) {
            throw new TicketException(
                    "panel.base-url в config.yml не похож на адрес — ожидается вида"
                            + " http://10.0.0.1:3001");
        }
    }

    public Result send(UUID playerUuid, String playerName, String text) throws TicketException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("playerUuid", Json.string(playerUuid.toString()));
        body.put("playerName", Json.string(playerName));
        body.put("text", Json.string(text));

        URI uri = endpoint("/api/internal/minecraft/servers/" + config.panelServerId() + "/tickets");

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
            // Токен в сообщение не попадает. Адрес — попадает, и намеренно:
            // это строка из config.yml того же сервера, а не секрет, и без
            // неё «панель недоступна» не отличить от опечатки в base-url.
            throw new TicketException(describeTransportFailure(e, uri));
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

    /**
     * Что именно не получилось на транспортном уровне.
     *
     * Раньше здесь стояло одно «панель недоступна» на все случаи, и по нему
     * нельзя было отличить закрытый порт от опечатки в адресе, а таймаут — от
     * несовпавшего сертификата. Это ровно те четыре причины, по которым тикеты
     * и перестают ходить, и каждая чинится по-разному.
     *
     * Токен сюда не попадает: он живёт только в заголовке запроса.
     */
    static String describeTransportFailure(Throwable error, URI uri) {
        String where = uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());

        // Порядок проверок — от частного к общему, и по ВСЕЙ цепочке причин,
        // а не по каждому звену подряд. Иначе неизвестный хост объявляется
        // закрытым портом, и человек идёт чинить файрвол вместо опечатки в
        // адресе. То же с таймаутом: HttpConnectTimeoutException САМ является
        // ConnectException.
        //
        // UnresolvedAddressException здесь не для красоты: в HttpClient из JDK
        // нерезолвящийся хост приходит НЕ как UnknownHostException, а как
        // ConnectException, вызванная UnresolvedAddressException из nio. Это
        // проверено запуском, а не выведено из документации.
        if (hasCause(error, UnknownHostException.class)
                || hasCause(error, UnresolvedAddressException.class)) {
            return "не удалось определить адрес " + uri.getHost()
                    + " — проверьте panel.base-url в config.yml";
        }
        if (hasCause(error, javax.net.ssl.SSLException.class)) {
            return "не удалось установить TLS-соединение с " + where
                    + " — по внутреннему адресу нужен http, а не https";
        }
        if (hasCause(error, HttpTimeoutException.class)) {
            return "панель не ответила вовремя (" + where
                    + ") — порт закрыт файрволом либо API слушает не тот адрес";
        }
        if (hasCause(error, ConnectException.class)) {
            return "не удалось подключиться к " + where
                    + " — панель слушает другой адрес или порт закрыт."
                    + " Частая причина: API_BIND у панели стоит 127.0.0.1,"
                    + " и снаружи VDS порт не виден даже через туннель";
        }
        // Соединение состоялось, ответа нет. Отдельная формулировка нужна
        // потому, что «недоступна» тут прямо вводит в заблуждение: сеть
        // исправна, и все проверки связи это подтвердят.
        if (hasCause(error, java.io.EOFException.class)) {
            return "панель разорвала соединение, не ответив (" + where
                    + ") — сеть при этом исправна, дело в самом обмене";
        }
        return "панель недоступна (" + where + "): " + error.getClass().getSimpleName();
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (type.isInstance(e)) return true;
        }
        return false;
    }

    /**
     * Проверка связи с панелью — для запуска плагина.
     *
     * Ходит в /api/health: он не требует ни токена, ни настроенного сервера,
     * поэтому отвечает на единственный вопрос «доходит ли вообще запрос до
     * панели». Про токен и id сервера скажет уже первый настоящий тикет —
     * и скажет отдельными формулировками.
     *
     * @return null, если панель отозвалась; иначе причина человеческими словами
     */
    public String checkPanel() {
        URI uri;
        try {
            uri = endpoint("/api/health");
        } catch (TicketException e) {
            return e.getMessage();
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return "панель ответила " + response.statusCode() + " на " + uri;
            }
            return null;
        } catch (Exception e) {
            return describeTransportFailure(e, uri);
        }
    }
}
