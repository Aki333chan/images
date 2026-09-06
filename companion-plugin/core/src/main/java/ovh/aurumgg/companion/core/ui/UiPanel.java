package ovh.aurumgg.companion.core.ui;

import java.util.List;

/** Compact HUD panel sent to the optional AurumUI Fabric client. */
public record UiPanel(String id, int priority, String title, List<String> lines) {
    public UiPanel {
        id = safe(id, 64);
        title = safe(title, 128);
        lines = lines == null ? List.of() : lines.stream().limit(24).map(line -> safe(line, 256)).toList();
    }

    private static String safe(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
