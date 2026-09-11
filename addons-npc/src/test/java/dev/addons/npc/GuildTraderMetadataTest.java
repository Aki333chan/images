package dev.addons.npc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class GuildTraderMetadataTest {
    @Test
    void pluginDeclaresOptionalGuildIntegrationAndPermission() throws Exception {
        try (var stream = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream);
            var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals("1.9.0", yaml.getString("version"));
            assertTrue(yaml.getStringList("depend").contains("AurumCore"));
            assertTrue(yaml.getStringList("softdepend").contains("AurumGuilds"));
            assertTrue(yaml.isConfigurationSection("permissions.addonsnpc.guildtrader"));
            assertTrue(yaml.isConfigurationSection("permissions.addonsnpc.exchanger"));
        }
    }

    @Test
    void defaultExchangerRepositoryResourceIsPresent() throws Exception {
        try (var stream = getClass().getResourceAsStream("/exchangers.yml")) {
            assertNotNull(stream);
            var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals(1, yaml.getInt("schema-version"));
            assertTrue(yaml.isConfigurationSection("exchangers"));
        }
    }

    @Test
    void defaultGuildTraderRepositoryResourceIsPresent() throws Exception {
        try (var stream = getClass().getResourceAsStream("/guild-traders.yml")) {
            assertNotNull(stream);
            var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals(1, yaml.getInt("schema-version"));
            assertTrue(yaml.isConfigurationSection("guild-traders"));
        }
    }

    @Test
    void currentNpcAndShopDefaultsArePresent() throws Exception {
        try (var configStream = getClass().getResourceAsStream("/config.yml");
             var shopsStream = getClass().getResourceAsStream("/shops.yml")) {
            assertNotNull(configStream);
            assertNotNull(shopsStream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(configStream, StandardCharsets.UTF_8));
            var shops = YamlConfiguration.loadConfiguration(new InputStreamReader(shopsStream, StandardCharsets.UTF_8));
            assertEquals(20L, config.getLong("settings.startup-spawn-delay-ticks"));
            assertEquals(4, shops.getInt("schema-version"));
        }
    }
}
