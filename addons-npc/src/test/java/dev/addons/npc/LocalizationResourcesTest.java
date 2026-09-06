package dev.addons.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LocalizationResourcesTest {
    @Test
    void shipsCompleteSupportedLocalesAndEnglishDefault() {
        YamlConfiguration config = load("/config.yml");
        assertEquals("en", config.getString("language"));
        for (String code : new String[]{"en", "pl", "ru"}) {
            YamlConfiguration locale = load("/locales/" + code + ".yml");
            assertNotNull(locale.getString("messages.prefix"));
            assertNotNull(locale.getString("gui.shop.price"));
            assertNotNull(locale.getString("gui.buyer.unit-price"));
            assertNotNull(locale.getString("gui.guild-trader.price"));
            assertNotNull(locale.getStringList("command-help"));
        }
    }

    private static YamlConfiguration load(String resource) {
        var stream = LocalizationResourcesTest.class.getResourceAsStream(resource);
        assertNotNull(stream, resource);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
