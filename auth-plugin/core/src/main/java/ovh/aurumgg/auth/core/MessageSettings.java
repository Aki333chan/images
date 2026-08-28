package ovh.aurumgg.auth.core;

import java.util.List;
import java.util.Map;

/**
 * Свои тексты: сообщения о входе-выходе и приветствие при заходе.
 *
 * ПОЧЕМУ ВСЁ ВЫКЛЮЧЕНО ПО УМОЛЧАНИЮ. На сервере, где стоит EssentialsX, этим
 * занимается он — и два плагина сразу дадут два сообщения о входе подряд.
 * Настройки здесь на случай, когда EssentialsX нет: тогда без них сервер
 * молчит вовсе, а это заметно хуже.
 *
 * Разбор вынесен из Bukkit и покрыт тестами: подстановка плейсхолдеров — ровно
 * тот код, который ломается молча. Ошибка в нём даёт не падение, а «{player}»
 * в чате у всех игроков.
 *
 * @param joinEnabled      показывать своё сообщение о входе
 * @param joinText         текст сообщения о входе
 * @param firstJoinText    текст для того, кто только что зарегистрировался
 * @param quitEnabled      показывать своё сообщение о выходе
 * @param quitText         текст сообщения о выходе
 * @param motdEnabled      показывать приветствие вошедшему
 * @param motdLines        строки приветствия
 */
public record MessageSettings(
        boolean joinEnabled,
        String joinText,
        String firstJoinText,
        boolean quitEnabled,
        String quitText,
        boolean motdEnabled,
        List<String> motdLines) {

    public static final String DEFAULT_JOIN = "&e{player} присоединился";
    public static final String DEFAULT_FIRST_JOIN = "&6Встречайте нового игрока: &e{player}&6!";
    public static final String DEFAULT_QUIT = "&e{player} вышел";
    public static final List<String> DEFAULT_MOTD = List.of(
            "&aДобро пожаловать, &f{player}&a!",
            "&7Сейчас на сервере: &f{online}&7/&f{max}");

    public static MessageSettings fromMap(Map<String, Object> raw) {
        return new MessageSettings(
                bool(raw, "messages.join.enabled"),
                text(raw, "messages.join.text", DEFAULT_JOIN),
                text(raw, "messages.join.first-time", DEFAULT_FIRST_JOIN),
                bool(raw, "messages.quit.enabled"),
                text(raw, "messages.quit.text", DEFAULT_QUIT),
                bool(raw, "messages.motd.enabled"),
                lines(raw, "messages.motd.lines", DEFAULT_MOTD));
    }

    /**
     * Подстановка значений в шаблон.
     *
     * Плейсхолдеры заменяются ОДНИМ проходом по шаблону, а не последовательными
     * replace: иначе ник вида «{online}» (а такие ники встречаются) при
     * следующей замене превратился бы в число. Мелочь, которая всплывает раз в
     * год и выглядит как мистика.
     */
    public static String apply(String template, Map<String, String> values) {
        if (template == null || template.isEmpty()) return "";
        StringBuilder result = new StringBuilder(template.length() + 16);
        int at = 0;
        while (at < template.length()) {
            int open = template.indexOf('{', at);
            if (open < 0) {
                result.append(template, at, template.length());
                break;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                result.append(template, at, template.length());
                break;
            }
            result.append(template, at, open);
            String key = template.substring(open + 1, close);
            String value = values.get(key);
            // Неизвестный плейсхолдер оставляем как есть: так опечатку в
            // конфиге видно сразу, а не «пропало слово».
            result.append(value != null ? value : template.substring(open, close + 1));
            at = close + 1;
        }
        return result.toString();
    }

    private static boolean bool(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value instanceof Boolean b) return b;
        return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static String text(Map<String, Object> raw, String key, String fallback) {
        Object value = raw.get(key);
        if (value == null) return fallback;
        String result = String.valueOf(value);
        // Пустая строка — осмысленное значение: «сообщение выключить, оставив
        // включённым остальное». Поэтому на дефолт она НЕ заменяется.
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(Map<String, Object> raw, String key, List<String> fallback) {
        Object value = raw.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof String single) return List.of(single);
        return fallback;
    }
}
