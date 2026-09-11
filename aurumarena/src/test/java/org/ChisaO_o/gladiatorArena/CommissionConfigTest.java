package org.ChisaO_o.gladiatorArena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Настройки комиссии: значения по умолчанию и разбор получателя.
 *
 * Умолчания здесь — не мелочь оформления. Комиссия с чемпионского пула,
 * включённая по недосмотру, молча урезала бы приз, который игроки сами
 * собрали взносами, и заметили бы это только они.
 */
class CommissionConfigTest {

    private static YamlConfiguration defaults() throws Exception {
        try (var stream = CommissionConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream, "config.yml отсутствует в ресурсах");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("По умолчанию комиссия идёт в казну и не трогает чемпионский пул")
    void safeDefaults() throws Exception {
        YamlConfiguration config = defaults();
        assertEquals("treasury", config.getString("economy.commission.destination"));
        assertFalse(config.getBoolean("economy.commission.apply-to-champion-pool", true));
        assertEquals(0.0, config.getDouble("economy.commission.percent"), 1e-9);
    }

    @Test
    @DisplayName("Резерв денег живёт секунды, а не между перезапусками")
    void holdTtlIsShort() throws Exception {
        long ttl = defaults().getLong("economy.hold-ttl-seconds");
        assertTrue(ttl >= 10 && ttl <= 3600, "TTL вне диапазона, разрешённого Core: " + ttl);
    }

    @Test
    @DisplayName("Получатель читается из написания, а опечатка не меняет смысл молча")
    void parsingIsForgivingButNotSilent() {
        assertEquals(GladiatorArena.CommissionTarget.CHAMPION_POOL,
                GladiatorArena.CommissionTarget.parse("champion-pool"));
        assertEquals(GladiatorArena.CommissionTarget.CHAMPION_POOL,
                GladiatorArena.CommissionTarget.parse("CHAMPION_POOL"));
        assertEquals(GladiatorArena.CommissionTarget.CHAMPION_POOL,
                GladiatorArena.CommissionTarget.parse("champions"));
        assertEquals(GladiatorArena.CommissionTarget.TREASURY,
                GladiatorArena.CommissionTarget.parse("treasury"));
        // Неизвестное значение — казна: это поведение 1.4.0, где комиссия
        // просто не доставалась игрокам. Тихо отдать её в пул было бы хуже.
        assertEquals(GladiatorArena.CommissionTarget.TREASURY,
                GladiatorArena.CommissionTarget.parse("tresury"));
        assertEquals(GladiatorArena.CommissionTarget.TREASURY,
                GladiatorArena.CommissionTarget.parse(null));
    }
}
