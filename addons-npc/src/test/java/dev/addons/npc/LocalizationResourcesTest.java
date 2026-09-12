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
            assertNotNull(locale.getString("gui.exchanger.confirm"));
            assertNotNull(locale.getString("messages.exchange-success"));
            assertNotNull(locale.getString("messages.aurum-economy-unavailable"));
            assertNotNull(locale.getStringList("tab.exchange-lore-examples"));
            assertNotNull(locale.getStringList("command-help"));
        }
    }

    /**
     * Точечные проверки выше ловят только то, о чём кто-то вспомнил. Настоящая
     * поломка выглядит иначе: добавили ключ в en, забыли в ru и pl, и игрок
     * видит сырой ключ вместо фразы. Английский здесь — эталон, потому что
     * именно он заполняется первым.
     */
    @Test
    void everyEnglishKeyExistsInEveryOtherLocale() {
        YamlConfiguration english = load("/locales/en.yml");
        var expected = english.getKeys(true);
        for (String code : new String[]{"pl", "ru"}) {
            YamlConfiguration locale = load("/locales/" + code + ".yml");
            var missing = expected.stream().filter(key -> !locale.contains(key)).sorted().toList();
            assertEquals(java.util.List.of(), missing, "в " + code + ".yml не хватает ключей");
        }
    }

    private static YamlConfiguration load(String resource) {
        var stream = LocalizationResourcesTest.class.getResourceAsStream(resource);
        assertNotNull(stream, resource);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
