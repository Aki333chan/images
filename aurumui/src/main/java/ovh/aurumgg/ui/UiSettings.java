package ovh.aurumgg.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Small client-only settings file; no server or mod-menu dependency needed. */
final class UiSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("aurumui.properties");

    boolean enabled = true;
    boolean arena = true;
    boolean party = true;
    boolean guilds = true;
    Scale scale = Scale.NORMAL;
    Opacity opacity = Opacity.NORMAL;

    static UiSettings load() {
        UiSettings settings = new UiSettings();
        if (!Files.isRegularFile(FILE)) return settings;
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(FILE)) {
            values.load(input);
            settings.enabled = bool(values, "enabled", true);
            settings.arena = bool(values, "panel.arena", true);
            settings.party = bool(values, "panel.party", true);
            settings.guilds = bool(values, "panel.guilds", true);
            settings.scale = enumValue(values, "scale", Scale.NORMAL, Scale.class);
            settings.opacity = enumValue(values, "opacity", Opacity.NORMAL, Opacity.class);
        } catch (IOException | IllegalArgumentException ignored) {
            // A damaged local config must never prevent the game from starting.
        }
        return settings;
    }

    void save() {
        Properties values = new Properties();
        values.setProperty("enabled", Boolean.toString(enabled));
        values.setProperty("panel.arena", Boolean.toString(arena));
        values.setProperty("panel.party", Boolean.toString(party));
        values.setProperty("panel.guilds", Boolean.toString(guilds));
        values.setProperty("scale", scale.name().toLowerCase(Locale.ROOT));
        values.setProperty("opacity", opacity.name().toLowerCase(Locale.ROOT));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream output = Files.newOutputStream(FILE)) {
                values.store(output, "AurumUI client settings");
            }
        } catch (IOException ignored) {
            // Settings remain active for this session even on a read-only client.
        }
    }

    void reset() {
        enabled = true;
        arena = true;
        party = true;
        guilds = true;
        scale = Scale.NORMAL;
        opacity = Opacity.NORMAL;
        save();
    }

    boolean shows(UiPanel panel) {
        if (!enabled) return false;
        String id = panel.id().toLowerCase(Locale.ROOT);
        if (id.contains("arena")) return arena;
        if (id.contains("party")) return party;
        if (id.contains("guild")) return guilds;
        return true;
    }

    private static boolean bool(Properties values, String key, boolean fallback) {
        String value = values.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static <T extends Enum<T>> T enumValue(
            Properties values, String key, T fallback, Class<T> type) {
        String value = values.getProperty(key);
        if (value == null) return fallback;
        return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    }

    enum Scale {
        COMPACT(0.85f, "screen.aurumui.scale.compact"),
        NORMAL(1.0f, "screen.aurumui.scale.normal"),
        LARGE(1.15f, "screen.aurumui.scale.large");

        final float factor;
        final String translation;

        Scale(float factor, String translation) {
            this.factor = factor;
            this.translation = translation;
        }
    }

    enum Opacity {
        LIGHT(112, "screen.aurumui.opacity.light"),
        NORMAL(176, "screen.aurumui.opacity.normal"),
        DARK(224, "screen.aurumui.opacity.dark");

        final int alpha;
        final String translation;

        Opacity(int alpha, String translation) {
            this.alpha = alpha;
            this.translation = translation;
        }
    }
}
