package ovh.aurumgg.core.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class LanguageFilesTest {
    @Test
    void everyLanguageContainsRequiredKeys() {
        Set<String> englishKeys = keys("en");
        for (String locale : new String[] {"en", "ru", "pl"}) {
            try (InputStream input = getClass().getResourceAsStream("/lang/messages_" + locale + ".yml")) {
                assertNotNull(input, locale);
                Map<String, Object> values = new Yaml().load(input);
                assertEquals(englishKeys, new TreeSet<>(values.keySet()), locale + " key set");
                for (String key : LanguageBundle.REQUIRED_KEYS) {
                    assertNotNull(values.get(key), locale + ":" + key);
                    assertFalse(values.get(key).toString().isBlank(), locale + ":" + key);
                }
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    @Test
    void existingOldLanguageFileFallsBackToBundledNewKeys(@TempDir Path directory) throws Exception {
        File existing = directory.resolve("messages_ru.yml").toFile();
        Files.writeString(existing.toPath(), "prefix: '&d[Custom] '\naccount-list-empty: '&7custom empty'\n");

        YamlConfiguration loaded = LanguageBundle.load(existing,
                getClass().getResourceAsStream("/lang/messages_ru.yml"));

        assertEquals("&d[Custom] ", loaded.getString("prefix"));
        assertEquals("&7custom empty", loaded.getString("account-list-empty"));
        assertEquals("&6Управляемые счета — страница %page%, всего %total%",
                loaded.getString("account-list-header"));
    }

    private Set<String> keys(String locale) {
        try (InputStream input = getClass().getResourceAsStream("/lang/messages_" + locale + ".yml")) {
            assertNotNull(input, locale);
            Map<String, Object> values = new Yaml().load(input);
            return new TreeSet<>(values.keySet());
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
