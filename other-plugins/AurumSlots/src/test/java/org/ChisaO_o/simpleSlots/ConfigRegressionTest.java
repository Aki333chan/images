package org.ChisaO_o.simpleSlots;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigRegressionTest {
    @Test
    void addingMissingDefaultsDoesNotDisableVault() {
        YamlConfiguration existing = new YamlConfiguration();
        existing.set("use_vault", true);

        InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertNotNull(stream);
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        );

        existing.setDefaults(defaults);
        existing.options().copyDefaults(true);

        assertTrue(existing.getBoolean("use_vault"));
        assertNotNull(existing.getString("messages.ru.vault_unavailable"));
    }

    @Test
    void hologramNumbersDoNotContainRedundantDecimalZero() {
        assertEquals("1", SimpleSlots.formatNumber(1.0));
        assertEquals("10", SimpleSlots.formatNumber(10.0));
        assertEquals("1.5", SimpleSlots.formatNumber(1.5));
        assertEquals("1000.25", SimpleSlots.formatNumber(1000.25));
    }

    @Test
    void recognizesLegacySlotHologramsForCleanup() {
        assertTrue(SimpleSlots.isLegacyHologramText("§eСтавка: §a10$"));
        assertTrue(SimpleSlots.isLegacyHologramText("§eСтавка: §a2 §fGOLD_NUGGET"));
    }
}
