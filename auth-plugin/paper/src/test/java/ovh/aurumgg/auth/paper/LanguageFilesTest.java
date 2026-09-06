package ovh.aurumgg.auth.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Файлы языка не расходятся между собой и с кодом.
 *
 * ЗАЧЕМ. Ключей полторы сотни, они написаны руками в трёх файлах, и ломаются
 * они тихо. Забытый в польском ключ подставится из русского — на польском
 * сервере появится русская фраза посреди своего текста, и выглядеть это будет
 * не как «перевода нет», а как «плагин сломался». Опечатка в имени ключа в
 * Java не падает вовсе: Messages честно вернёт сам ключ, и в чат уедет
 * «admin.unregister.donee».
 *
 * Bukkit здесь не нужен: yaml разбирается тем же snakeyaml, что и внутри
 * сервера, а исходники читаются как текст.
 */
class LanguageFilesTest {

    private static final List<String> LANGUAGES = List.of("ru", "en", "pl");

    /**
     * Ключи, которые просит код.
     *
     * Ищем вызовы вида plugin.text("…") и plugin.lines("…") — именно так слой
     * Bukkit спрашивает тексты. Ключи из core (исходы входа) сюда не попадают
     * и проверяются отдельно: там они лежат в литералах рядом с условием.
     */
    private static final Pattern ASKED = Pattern.compile("plugin\\.(?:text|lines)\\(\"([\\w.]+)\"");

    private static Map<String, Object> flatten(String prefix, Map<?, ?> node, Map<String, Object> into) {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey())
                    : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(key, nested, into);
            } else {
                into.put(key, value);
            }
        }
        return into;
    }

    private static Map<String, Object> load(String language) {
        Path file = Path.of("src/main/resources/lang/messages_" + language + ".yml");
        try (InputStream stream = Files.newInputStream(file)) {
            Map<?, ?> root = new Yaml().load(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
            return flatten("", root, new java.util.HashMap<>());
        } catch (IOException e) {
            throw new IllegalStateException("не читается " + file, e);
        }
    }

    /** Плейсхолдеры {вида} в значении — по ним сверяются переводы. */
    private static Set<String> placeholders(Object value) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = Pattern.compile("\\{(\\w+)\\}").matcher(String.valueOf(value));
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    @Test
    @DisplayName("Во всех языках один и тот же набор ключей")
    void ключиСовпадают() {
        Map<String, Object> ru = load("ru");
        assertTrue(ru.size() > 100, "ключей подозрительно мало: " + ru.size());

        for (String language : LANGUAGES) {
            if (language.equals("ru")) continue;
            Map<String, Object> other = load(language);

            assertEquals(List.of(), new TreeSet<>(ru.keySet()).stream()
                            .filter(key -> !other.containsKey(key)).toList(),
                    "нет в " + language);
            // Лишний ключ — обычно опечатка в имени: перевод есть, но берётся
            // он никогда, потому что код просит другое имя.
            assertEquals(List.of(), new TreeSet<>(other.keySet()).stream()
                            .filter(key -> !ru.containsKey(key)).toList(),
                    "лишнее в " + language);
        }
    }

    @Test
    @DisplayName("Подстановки в переводе те же, что в русском")
    void подстановкиСовпадают() {
        // Потерянный при переводе {name} — это фраза без имени игрока, а
        // лишний {nmae} останется в чате фигурными скобками.
        Map<String, Object> ru = load("ru");
        for (String language : LANGUAGES) {
            if (language.equals("ru")) continue;
            Map<String, Object> other = load(language);
            List<String> mismatched = new ArrayList<>();
            for (Map.Entry<String, Object> entry : ru.entrySet()) {
                Object translated = other.get(entry.getKey());
                if (translated == null) continue;
                if (!placeholders(entry.getValue()).equals(placeholders(translated))) {
                    mismatched.add(entry.getKey());
                }
            }
            assertEquals(List.of(), mismatched, "подстановки разошлись в " + language);
        }
    }

    @Test
    @DisplayName("Каждый ключ, который просит код, есть в файле языка")
    void кодНеПроситЛишнего() throws IOException {
        Map<String, Object> ru = load("ru");
        Set<String> asked = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher matcher = ASKED.matcher(Files.readString(file));
                while (matcher.find()) asked.add(matcher.group(1));
            }
        }

        // Ключи вообще нашлись: пустое множество означало бы, что регулярка
        // перестала совпадать, а тест — проходить впустую.
        assertTrue(asked.size() > 50, "ключей в коде подозрительно мало: " + asked.size());
        assertEquals(List.of(), asked.stream().filter(key -> !ru.containsKey(key)).toList(),
                "код просит ключи, которых нет в messages_ru.yml");
    }
}
