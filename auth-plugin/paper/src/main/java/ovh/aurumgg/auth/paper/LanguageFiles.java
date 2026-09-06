package ovh.aurumgg.auth.paper;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.auth.core.Messages;

/**
 * Загрузка файлов с текстами.
 *
 * ВСЕ ЯЗЫКИ РАСПАКОВЫВАЮТСЯ СРАЗУ, а не только выбранный. Админ должен видеть,
 * что варианты есть, и иметь возможность заглянуть в чужой файл за
 * формулировкой; иначе о существовании английского узнают, только прочитав
 * README до конца.
 *
 * ФАЙЛЫ НЕ ПЕРЕЗАПИСЫВАЮТСЯ при обновлении плагина: распаковывается только то,
 * чего на диске ещё нет.
 * Перезапись стирала бы ровно то, ради чего файлы и заведены, — правки
 * админа. Недостающие после обновления ключи берутся из встроенного русского,
 * поэтому новые сообщения появляются сами, а старые остаются какими были.
 */
final class LanguageFiles {

    private LanguageFiles() {}

    /** Читает выбранный язык и встроенный русский как запасной. */
    static Messages load(Plugin plugin, String language) {
        for (String known : Messages.LANGUAGES) {
            // Существование проверяем САМИ, хотя второй аргумент saveResource
            // и означает «не перезаписывать». Он не перезаписывает, но при
            // этом пишет в консоль WARNING «... already exists» — на каждый
            // язык, при каждом старте и каждой перезагрузке. То есть ровно
            // тогда, когда всё в порядке: файлы на месте, потому что админ их
            // правил. Предупреждение о нормальной работе учит не читать
            // предупреждения.
            File file = new File(plugin.getDataFolder(), path(known));
            if (!file.isFile()) plugin.saveResource(path(known), false);
        }

        Map<String, Object> chosen = fromFile(plugin, language);
        // Запасной — ИЗ JAR, а не с диска: он должен быть полным. Русский файл
        // на диске админ правит так же, как и остальные, и вычеркнутая там
        // строка не должна утащить за собой сообщение на другом языке.
        Map<String, Object> fallback = fromJar(plugin, Messages.DEFAULT_LANGUAGE);
        return new Messages(chosen.isEmpty() ? fallback : chosen, fallback);
    }

    private static String path(String language) {
        return "lang/messages_" + language + ".yml";
    }

    private static Map<String, Object> fromFile(Plugin plugin, String language) {
        File file = new File(plugin.getDataFolder(), path(language));
        if (!file.isFile()) return fromJar(plugin, language);
        return flatten(YamlConfiguration.loadConfiguration(file));
    }

    private static Map<String, Object> fromJar(Plugin plugin, String language) {
        try (InputStream stream = plugin.getResource(path(language))) {
            if (stream == null) return Map.of();
            return flatten(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось прочитать встроенный файл " + path(language)
                    + ": " + e.getMessage());
            return Map.of();
        }
    }

    /**
     * Плоская карта «login.success → строка».
     *
     * getValues(true) отдаёт и промежуточные узлы («login» → секция), они нам
     * не нужны: значение по такому ключу — объект конфигурации, и подстановка
     * превратила бы его в «MemorySection[path='login']» в чате.
     */
    private static Map<String, Object> flatten(YamlConfiguration yaml) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : yaml.getValues(true).entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String || value instanceof java.util.List<?>) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }
}
