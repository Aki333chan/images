package ovh.aurumgg.auth.core.premium;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import ovh.aurumgg.auth.api.PremiumVerdict;

/**
 * Определение premium-игрока: существует ли лицензионная учётка с таким ником
 * и подтвердил ли кто-то выше вход под ней.
 *
 * Решение и поход в сеть намеренно разделены. {@link #decide} — чистая
 * функция от того, что вернул Mojang, и её можно проверить тестами на всех
 * важных случаях, включая те, что в жизни ловятся редко и дорого. Сетевая
 * часть тонкая и заменяемая: в тестах вместо неё подставляется свой
 * {@link ProfileLookup}.
 */
public final class PremiumChecker {

    /**
     * Эндпоинт Mojang: ник → профиль.
     *
     * 200 и тело с id — учётка есть, 404 — нет. Адрес вынесен в конфиг:
     * Mojang свои API переносил уже не раз, и захардкоженная строка означала бы
     * выпуск новой версии плагина ради одной ссылки.
     */
    public static final String DEFAULT_ENDPOINT = "https://api.mojang.com/users/profiles/minecraft/";

    /** Откуда берётся профиль по нику. Отдельным интерфейсом ради тестов. */
    public interface ProfileLookup {
        /** Профиль или пусто, если учётки нет. Бросает исключение, если спросить не удалось. */
        Optional<MojangProfile> byName(String username) throws Exception;
    }

    private final ProfileLookup lookup;
    private final boolean enabled;
    private final Duration cacheTtl;
    /** Ник (в нижнем регистре) → что ответил Mojang и когда. */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public PremiumChecker(ProfileLookup lookup, boolean enabled, Duration cacheTtl) {
        this.lookup = lookup;
        this.enabled = enabled;
        this.cacheTtl = cacheTtl;
    }

    /** Обычный вариант: реальные запросы к Mojang. */
    public static PremiumChecker overNetwork(String endpoint, Duration timeout, boolean enabled, Duration cacheTtl) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                // Редиректы Mojang не использует, а следование за ними на
                // запросе, который решает «пускать без пароля», — лишняя
                // поверхность.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new PremiumChecker(
                username -> request(client, endpoint, username, timeout), enabled, cacheTtl);
    }

    /**
     * Допустимый ник Mojang: буквы, цифры и подчёркивание, не длиннее 16.
     *
     * Ник подставляется прямо в путь запроса, и проверять его надёжнее, чем
     * экранировать: ник, который у Mojang существовать не может, заведомо не
     * принадлежит лицензии — спрашивать про него нечего, а вот пустить в URL
     * произвольную строку с «../» или пробелами очень не хочется.
     */
    private static final java.util.regex.Pattern SAFE_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");

    private static Optional<MojangProfile> request(
            HttpClient client, String endpoint, String username, Duration timeout) throws Exception {
        if (!SAFE_NAME.matcher(username).matches()) return Optional.empty();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + username))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return MojangProfile.parse(response.statusCode(), response.body());
    }

    /**
     * Вердикт по подключению.
     *
     * Вызывается ТОЛЬКО из асинхронного контекста (AsyncPlayerPreLoginEvent):
     * внутри поход в сеть, и на главном потоке ему делать нечего.
     */
    public PremiumVerdict check(UUID connectionUuid, String username) {
        if (!enabled) return PremiumVerdict.UNKNOWN;

        String key = username.toLowerCase(Locale.ROOT);
        CacheEntry cached = cache.get(key);
        long now = System.nanoTime();
        if (cached != null && now - cached.at < cacheTtl.toNanos()) {
            return decide(connectionUuid, username, cached.profile);
        }

        Optional<MojangProfile> profile;
        try {
            profile = lookup.byName(username);
        } catch (Exception e) {
            // Mojang недоступен. Кэшировать неудачу нельзя: иначе одна минута
            // сетевых проблем на несколько минут закрепила бы «не premium»
            // за всеми, кто заходил в это время.
            return PremiumVerdict.UNKNOWN;
        }
        cache.put(key, new CacheEntry(profile.orElse(null), now));
        return decide(connectionUuid, username, profile.orElse(null));
    }

    /**
     * Чистое решение по тому, что известно о подключении.
     *
     * ВСЯ СУТЬ ЗДЕСЬ, И ОНА В ОДНОМ СРАВНЕНИИ. Совпадение UUID подключения с
     * настоящим UUID из Mojang невозможно подделать offline-клиентом: в
     * offline-mode UUID вычисляет сам сервер из ника, и получается заведомо
     * другой. Значит, совпадение означает, что UUID подставило вышестоящее
     * звено — прокси, проведшее настоящую online-mode авторизацию.
     *
     * Всё остальное — только «ник числится за лицензией», и пароль по этому
     * основанию не пропускается. См. подробности в PremiumVerdict.
     */
    public static PremiumVerdict decide(UUID connectionUuid, String username, MojangProfile profile) {
        if (profile == null) return PremiumVerdict.OFFLINE_NAME;
        if (connectionUuid != null && connectionUuid.equals(profile.uuid())) {
            return PremiumVerdict.PREMIUM_VERIFIED;
        }
        return PremiumVerdict.PREMIUM_NAME_ONLY;
    }

    /**
     * UUID, который offline-сервер выдаёт игроку по нику.
     *
     * Формула не наша: так её считает ванильный LoginListener, и от неё
     * зависит совместимость данных со всем остальным на сервере.
     */
    public static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    /** Забыть ответ по нику — нужно после смены владельца ника. */
    public void forget(String username) {
        cache.remove(username.toLowerCase(Locale.ROOT));
    }

    private record CacheEntry(MojangProfile profile, long at) {}
}
