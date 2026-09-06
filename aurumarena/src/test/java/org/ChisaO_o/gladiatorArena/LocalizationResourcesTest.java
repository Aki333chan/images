package org.ChisaO_o.gladiatorArena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

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

    private static YamlConfiguration load(String resource) {
        var stream = LocalizationResourcesTest.class.getResourceAsStream(resource);
        assertNotNull(stream, resource);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
