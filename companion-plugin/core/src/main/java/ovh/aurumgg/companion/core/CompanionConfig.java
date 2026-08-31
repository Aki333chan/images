package ovh.aurumgg.companion.core;

/**
 * Настройки плагина. Заполняются из config.yml (см. модуль paper).
 *
 * @param bindAddress    адрес прослушивания; внутри контейнера Pterodactyl это
 *                       обычно 0.0.0.0, а закрытость порта обеспечивает
 *                       allocation на приватном интерфейсе + файрвол
 * @param port           порт входящего HTTP (secondary allocation в Pterodactyl)
 * @param token          общий секрет: им панель авторизуется в плагине,
 *                       а плагин — в панели
 * @param panelBaseUrl   внутренний адрес панели, напр. http://10.0.0.1:3001
 *                       (публичный домен использовать не нужно)
 * @param panelServerId  id сервера в панели (UUID из её адресной строки)
 * @param ticketCooldownSeconds пауза между командами /ticket от одного игрока
 */
public record CompanionConfig(
        String bindAddress,
        int port,
        String token,
        String panelBaseUrl,
        String panelServerId,
        int ticketCooldownSeconds) {

    /** Значение из шаблона config.yml: с ним плагин запускаться не должен. */
    public static final String PLACEHOLDER_TOKEN = "CHANGE_ME";

    public static final int MIN_TOKEN_LENGTH = 16;

    /**
     * Причина, по которой запускать HTTP-сервер нельзя, или null, если всё в порядке.
     * Проверяется на старте — лучше не подняться совсем, чем слушать порт
     * с предсказуемым токеном.
     */
    public String httpConfigProblem() {
        if (token == null || token.isBlank() || PLACEHOLDER_TOKEN.equals(token)) {
            return "в config.yml не задан token — плагин не будет принимать запросы панели";
        }
        if (token.length() < MIN_TOKEN_LENGTH) {
            return "token короче " + MIN_TOKEN_LENGTH + " символов — задайте длинный случайный секрет";
        }
        // Токен уходит в HTTP-заголовок Authorization, а заголовки обязаны быть
        // ASCII: с кириллицей запрос упал бы уже в рантайме, при первом /ticket.
        if (!isAscii(token)) {
            return "token содержит не-ASCII символы — используйте вывод `openssl rand -base64 32`";
        }
        if (port <= 0 || port > 65535) {
            return "некорректный порт " + port;
        }
        return null;
    }

    /** Причина, по которой нельзя отправлять тикеты в панель, или null. */
    public String ticketConfigProblem() {
        if (panelBaseUrl == null || panelBaseUrl.isBlank()) {
            return "не задан panel.base-url";
        }
        if (panelServerId == null || panelServerId.isBlank()) {
            return "не задан panel.server-id";
        }
        return httpConfigProblem() == null ? null : "некорректный token";
    }

    /**
     * Подозрительное в настройке тикетов — предупредить, но не отключать.
     *
     * В config.yml два порта, и их путают: `http.port` — порт, который слушает
     * САМ ПЛАГИН (его же выдают secondary allocation в Pterodactyl), а порт в
     * `panel.base-url` — порт ПАНЕЛИ на другой машине. Совпадение почти всегда
     * означает, что второй заполнили значением первого, и тогда плагин стучится
     * в собственный порт на чужом хосте: соединения нет, а сообщение об этом
     * («не удалось подключиться») отправляет чинить сеть, в которой всё цело.
     *
     * Именно предупреждение, а не отказ: теоретически панель может слушать тот
     * же номер порта на своей машине, и запрещать это плагину не за что.
     *
     * @return текст предупреждения или null
     */
    public String ticketConfigWarning() {
        if (panelBaseUrl == null || panelBaseUrl.isBlank()) return null;
        if (!endsWithPort(panelBaseUrl, port)) return null;
        return "в panel.base-url стоит порт " + port + " — это порт самого плагина (http.port),"
                + " а нужен порт панели (обычно 3001). Похоже, поля перепутаны местами";
    }

    /** Порт в конце адреса, без разбора URI: адрес может быть и вовсе не адресом. */
    private static boolean endsWithPort(String url, int port) {
        String suffix = ":" + port;
        String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return trimmed.endsWith(suffix);
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // Печатаемый диапазон ASCII: управляющие символы в заголовке тоже недопустимы.
            if (c < 0x21 || c > 0x7e) return false;
        }
        return true;
    }
}
