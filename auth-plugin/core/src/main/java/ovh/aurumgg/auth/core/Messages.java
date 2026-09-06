package ovh.aurumgg.auth.core;

import java.util.List;
import java.util.Map;

/**
 * Тексты, которые видит игрок.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЕ ФАЙЛЫ, А НЕ СТРОКИ В КОДЕ. Раньше все реплики плагина жили
 * прямо в Java: чтобы поправить одну формулировку, нужно было пересобрать jar
 * и перезалить его на сервер. На живом сервере так не делают ради запятой,
 * поэтому формулировки не правились вовсе. Теперь они лежат в
 * lang/messages_<язык>.yml рядом с config.yml — там же, где админ правит всё
 * остальное, и правка применяется перезагрузкой конфига.
 *
 * ЯЗЫК ВЫБИРАЕТСЯ ОДИН НА СЕРВЕР, а не на игрока. Так это работает во всех
 * плагинах Bukkit: сервер знает про клиента только его локаль в настройках, и
 * то не всегда, а чат один на всех — сообщение о входе видят все сразу.
 * Выбирать язык на каждого значило бы обещать то, чего протокол не даёт.
 *
 * ЗАПАСНОЙ ЯЗЫК — ВСЕГДА РУССКИЙ. Ключ, которого нет в выбранном файле (файл
 * от старой версии плагина, админ удалил строку, опечатался в имени),
 * подставляется из встроенного русского. Пустая строка на месте реплики
 * выглядит как поломка сервера, а сам ключ — как ошибка разработчика; и то,
 * и другое хуже, чем фраза не на том языке.
 */
public final class Messages {

    /** Языки, для которых в jar лежат готовые файлы. */
    public static final List<String> LANGUAGES = List.of("ru", "en", "pl");

    /** Язык по умолчанию — он же запасной. */
    public static final String DEFAULT_LANGUAGE = "ru";

    private final Map<String, Object> chosen;
    private final Map<String, Object> fallback;

    /**
     * @param chosen   плоская карта «ключ → строка или список» выбранного языка
     * @param fallback то же для русского; для русского совпадает с chosen
     */
    public Messages(Map<String, Object> chosen, Map<String, Object> fallback) {
        this.chosen = Map.copyOf(chosen);
        this.fallback = Map.copyOf(fallback);
    }

    /** Язык из конфига, приведённый к известному. Незнакомый — русский. */
    public static String normalizeLanguage(String raw) {
        if (raw == null) return DEFAULT_LANGUAGE;
        String value = raw.trim().toLowerCase();
        return LANGUAGES.contains(value) ? value : DEFAULT_LANGUAGE;
    }

    /** Строка без подстановок. */
    public String get(String key) {
        return get(key, Map.of());
    }

    /**
     * Строка с подстановками вида {player}.
     *
     * Ключа нет нигде — возвращается он сам. Это заметно при первом же взгляде
     * в чат и однозначно указывает, что именно потерялось; пустая строка
     * выглядела бы как «плагин промолчал», и искать причину пришлось бы долго.
     */
    public String get(String key, Map<String, String> values) {
        Object raw = chosen.get(key);
        if (raw == null) raw = fallback.get(key);
        if (raw == null) return key;
        return MessageSettings.apply(String.valueOf(raw), values);
    }

    /** Список строк — для многострочных приветствий и справки. */
    public List<String> list(String key) {
        return list(key, Map.of());
    }

    public List<String> list(String key, Map<String, String> values) {
        Object raw = chosen.get(key);
        if (raw == null) raw = fallback.get(key);
        if (raw instanceof List<?> list) {
            return list.stream().map(item -> MessageSettings.apply(String.valueOf(item), values)).toList();
        }
        if (raw == null) return List.of(key);
        return List.of(MessageSettings.apply(String.valueOf(raw), values));
    }

    /** Есть ли ключ хоть где-нибудь: нужно для проверки полноты файлов. */
    public boolean has(String key) {
        return chosen.containsKey(key) || fallback.containsKey(key);
    }

    /** Ключи запасного языка — полный список того, что плагин умеет говорить. */
    public java.util.Set<String> knownKeys() {
        return fallback.keySet();
    }
}
