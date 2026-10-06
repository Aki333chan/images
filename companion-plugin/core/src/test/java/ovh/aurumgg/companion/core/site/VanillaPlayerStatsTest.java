package ovh.aurumgg.companion.core.site;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanillaPlayerStatsTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();

    @Test void readsSavedCountersAndOmittedZeros() throws Exception {
        Files.writeString(directory.resolve(player + ".json"), "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":72000,\"minecraft:player_kills\":2}}}");
        var stats = VanillaPlayerStats.read(directory, player);
        assertFalse(stats.online());
        assertEquals(72000L, stats.playTimeTicks());
        assertEquals(0L, stats.deaths());
        assertEquals(2L, stats.playerKills());
    }

    @Test void missingCorruptOversizedOrInvalidFileNeverBecomesZero() throws Exception {
        assertNull(VanillaPlayerStats.read(directory, player).playTimeTicks());
        for (String json : new String[]{"broken", "{}", "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":-1}}}", " ".repeat(512 * 1024 + 1)}) {
            Files.writeString(directory.resolve(player + ".json"), json);
            assertNull(VanillaPlayerStats.read(directory, player).playTimeTicks());
        }
    }
}
