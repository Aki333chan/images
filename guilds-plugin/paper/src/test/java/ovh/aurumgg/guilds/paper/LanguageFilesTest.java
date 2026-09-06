package ovh.aurumgg.guilds.paper;

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
 * ЗАЧЕМ. Ключей три сотни, они написаны руками в трёх файлах, и ломаются они
 * тихо. Забытый в польском ключ подставится из русского — на польском сервере
 * появится русская фраза посреди своего текста, и выглядеть это будет не как
 * «перевода нет», а как «плагин сломался». Опечатка в имени ключа в Java не
 * падает вовсе: Messages честно вернёт сам ключ, и в чат уедет
 * «guild.err.notInGuidl».
 *
 * Bukkit здесь не нужен: yaml разбирается тем же snakeyaml, что и внутри
 * сервера, а исходники читаются как текст.
 */
class LanguageFilesTest {

    private static final List<String> LANGUAGES = List.of("ru", "en", "pl");

    /**
     * Ключи, которые просит код.
     *
     * Msg.text/Msg.lines — как спрашивает тексты слой Bukkit; plugin.text и
     * plugin.lines — как их спрашивает сам плагин; и отдельно ключи из core,
     * которые уезжают в GuildActionResult и в HudLines.Labels: они лежат
     * литералами рядом с условием, при котором возникают, и в файле языка
     * обязаны быть ровно так же.
     *
     * Запятая или скобка после кавычки — не украшение: без них сюда попадали
     * бы и половинки склеенных ключей вроде {@code "help.guild." + entry},
     * которые ключами не являются и в словаре быть не должны.
     */
    private static final Pattern ASKED = Pattern.compile(
            "(?:Msg|plugin|owner)\\.(?:text|lines)\\(\"([\\w.]+)\"\\s*[,)]");

    /**
     * То же самое для core: результаты действий и подписи сайдбара.
     *
     * Отдельной регуляркой, потому что там ключ стоит не в вызове текста, а в
     * GuildActionResult.ok/fail, Verdict.bad и t.get — по одной форме их не
     * поймать.
     */
    private static final Pattern ASKED_CORE = Pattern.compile(
            "(?:GuildActionResult\\.(?:ok|fail)|Verdict\\.bad|t\\.get)"
                    + "\\(\\s*\"([\\w.]+)\"\\s*[,)]");

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
        assertTrue(ru.size() > 250, "ключей подозрительно мало: " + ru.size());

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
        // Потерянный при переводе {player} — это фраза без имени игрока, а
        // лишний {palyer} останется в чате фигурными скобками.
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
        collect(Path.of("src/main/java"), ASKED, asked);
        // core лежит рядом: ключи оттуда доезжают до игрока через тот же
        // словарь, и промах в них так же не виден до самого чата.
        collect(Path.of("../core/src/main/java"), ASKED, asked);
        collect(Path.of("../core/src/main/java"), ASKED_CORE, asked);

        // Ключи вообще нашлись: пустое множество означало бы, что регулярка
        // перестала совпадать, а тест — проходить впустую.
        assertTrue(asked.size() > 150, "ключей в коде подозрительно мало: " + asked.size());
        assertEquals(List.of(), asked.stream().filter(key -> !ru.containsKey(key)).toList(),
                "код просит ключи, которых нет в messages_ru.yml");
    }

    private static void collect(Path root, Pattern pattern, Set<String> into) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher matcher = pattern.matcher(Files.readString(file));
                while (matcher.find()) into.add(matcher.group(1));
            }
        }
    }
}
