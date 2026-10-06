package ovh.aurumgg.companion.core.site;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import ovh.aurumgg.companion.core.json.JsonParser;
import ovh.aurumgg.companion.core.model.SitePlayerInfo;

/** Read one saved 26.x player file on the HTTP worker, never on the game thread. */
public final class VanillaPlayerStats {
    private static final int MAX_BYTES = 512 * 1024;

    private VanillaPlayerStats() {}

    public static SitePlayerInfo read(Path statsDirectory, UUID player) {
        SitePlayerInfo unavailable = new SitePlayerInfo(false, null, null, null);
        Path file = statsDirectory.resolve(player + ".json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return unavailable;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) return unavailable;
            Map<String, Object> root = JsonParser.parseObject(new String(bytes, StandardCharsets.UTF_8));
            if (!(root.get("stats") instanceof Map<?, ?> stats)
                    || !(stats.get("minecraft:custom") instanceof Map<?, ?> custom)) return unavailable;
            return new SitePlayerInfo(false, counter(custom, "minecraft:play_time"),
                    counter(custom, "minecraft:deaths"), counter(custom, "minecraft:player_kills"));
        } catch (IOException | IllegalArgumentException ex) {
            // A file can be absent or briefly being saved; do not invent statistics.
            return unavailable;
        }
    }

    private static Long counter(Map<?, ?> custom, String key) {
        Object raw = custom.get(key);
        if (raw == null) return 0L; // Minecraft omits counters which have never increased.
        if (!(raw instanceof Number number)) throw new IllegalArgumentException("Invalid counter");
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0 || value > Integer.MAX_VALUE || value != Math.rint(value))
            throw new IllegalArgumentException("Invalid counter");
        return (long) value;
    }
}
