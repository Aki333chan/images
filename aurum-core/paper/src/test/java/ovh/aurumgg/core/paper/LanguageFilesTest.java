package ovh.aurumgg.core.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class LanguageFilesTest {
    @Test
    void everyLanguageContainsRequiredKeys() {
        for (String locale : new String[] {"en", "ru", "pl"}) {
            try (InputStream input = getClass().getResourceAsStream("/lang/messages_" + locale + ".yml")) {
                assertNotNull(input, locale);
                Map<String, Object> values = new Yaml().load(input);
                for (String key : LanguageBundle.REQUIRED_KEYS) {
                    assertNotNull(values.get(key), locale + ":" + key);
                    assertFalse(values.get(key).toString().isBlank(), locale + ":" + key);
                }
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
