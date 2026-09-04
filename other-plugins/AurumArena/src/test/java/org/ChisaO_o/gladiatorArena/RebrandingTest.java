package org.ChisaO_o.gladiatorArena;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RebrandingTest {
    @TempDir Path root;

    @Test void copiesAllLegacyDataWithoutChangingOriginals() throws Exception {
        Path old = Files.createDirectory(root.resolve("old"));
        Files.createDirectory(old.resolve("nested"));
        byte[] bytes = new byte[] {0, 1, 42, (byte)255};
        Files.write(old.resolve("nested/statistics.db"), bytes);
        Files.writeString(old.resolve("config.yml"), "use_vault: true\n");
        Path renamed = root.resolve("AurumArena");
        assertTrue(LegacyDataMigration.migrate(old, renamed));
        assertArrayEquals(bytes, Files.readAllBytes(renamed.resolve("nested/statistics.db")));
        assertArrayEquals(bytes, Files.readAllBytes(old.resolve("nested/statistics.db")));
        assertEquals("use_vault: true\n", Files.readString(renamed.resolve("config.yml")));
        assertFalse(Files.exists(root.resolve(".AurumArena-migration")));
    }

    @Test void neverOverwritesAnExistingNewInstallation() throws Exception {
        Path old = Files.createDirectory(root.resolve("old"));
        Path renamed = Files.createDirectory(root.resolve("AurumArena"));
        Files.writeString(old.resolve("config.yml"), "old");
        Files.writeString(renamed.resolve("config.yml"), "new");
        assertFalse(LegacyDataMigration.migrate(old, renamed));
        assertEquals("new", Files.readString(renamed.resolve("config.yml")));
    }

    @Test void missingLegacyFolderIsANormalFreshInstall() throws Exception {
        assertFalse(LegacyDataMigration.migrate(root.resolve("missing"), root.resolve("AurumArena")));
        assertFalse(Files.exists(root.resolve("AurumArena")));
    }

    @Test void incompleteMigrationDoesNotBecomeTheLiveDataFolder() throws Exception {
        Path old = Files.createDirectory(root.resolve("old"));
        Files.createDirectory(root.resolve(".AurumArena-migration"));
        assertThrows(java.io.IOException.class, () -> LegacyDataMigration.migrate(old, root.resolve("AurumArena")));
        assertFalse(Files.exists(root.resolve("AurumArena")));
    }

    @Test void pluginMetadataUsesTheNewBrandAndPreservesOldCommands() throws Exception {
        try (var stream = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream);
            var metadata = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals("AurumArena", metadata.getString("name"));
            assertEquals("1.1.5", metadata.getString("version"));
            assertEquals("org.ChisaO_o.gladiatorArena.GladiatorArena", metadata.getString("main"));
            assertTrue(metadata.getStringList("commands.arena.aliases").contains("aurumarena"));
        }
    }
}
