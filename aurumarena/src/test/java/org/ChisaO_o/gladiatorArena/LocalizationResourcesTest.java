package org.ChisaO_o.gladiatorArena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Локализация арены устроена подстановками: русские строки зашиты в коде, а
 * en/pl подменяют их по точному совпадению. Схема хрупкая ровно в одном
 * месте — стоит поправить русскую строку, и перевод молча перестаёт
 * находиться, а игрок видит русский текст в английском клиенте. Сборка при
 * этом зелёная. Поэтому совпадение проверяется тестом.
 */
class LocalizationResourcesTest {
    @Test
    void shipsSupportedLocalesAndEnglishDefault() {
        assertEquals("en", load("/config.yml").getString("language"));
        for (String code : new String[]{"en", "pl", "ru"}) {
            YamlConfiguration locale = load("/locales/" + code + ".yml");
            assertNotNull(locale.getString("messages.prefix"));
            assertNotNull(locale.getString("messages.final-stats"));
            assertFalse(locale.getStringList("help.player").isEmpty());
            assertFalse(locale.getStringList("help.admin").isEmpty());
        }
    }

    @Test
    @DisplayName("Каждая подстановка en/pl находит свою строку в исходниках")
    void everyReplacementStillMatchesSource() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/org/ChisaO_o/gladiatorArena/GladiatorArena.java"),
                StandardCharsets.UTF_8);
        for (String code : new String[]{"en", "pl"}) {
            List<String> orphans = new ArrayList<>();
            for (Map<?, ?> entry : load("/locales/" + code + ".yml").getMapList("replacements")) {
                Object from = entry.get("from");
                if (from == null) continue;
                String text = String.valueOf(from);
                // Цветовые коды в исходниках записаны как есть, поэтому
                // сравниваем подстроку буквально.
                if (!source.contains(text)) orphans.add(text);
            }
            assertTrue(orphans.isEmpty(),
                    code + ": подстановки не находят свою строку в GladiatorArena.java — "
                            + "перевод к ним уже не применится: " + orphans);
        }
    }

    private static YamlConfiguration load(String resource) {
        var stream = LocalizationResourcesTest.class.getResourceAsStream(resource);
        assertNotNull(stream, resource);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
