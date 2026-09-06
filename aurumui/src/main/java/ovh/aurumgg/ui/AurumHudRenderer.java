package ovh.aurumgg.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

final class AurumHudRenderer {
    private static final int RIGHT_MARGIN = 6;
    private static final int TOP_MARGIN = 7;
    private static final int PANEL_GAP = 5;
    private static final int PAD_X = 6;
    private static final int PAD_Y = 4;
    private static final int MAX_WIDTH = 178;
    private static final int MIN_PANEL_HEIGHT = PAD_Y * 2 + 20;
    private static final int BG_RGB = 0x00121016;
    private static final int EDGE = 0xFFE6A62F;
    private static final int TITLE = 0xFFFFC85C;
    private static final int TEXT = 0xFFF2F2F2;

    private AurumHudRenderer() {}

    static void render(GuiGraphicsExtractor graphics, List<UiPanel> source, UiSettings settings) {
        if (source == null || source.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        List<UiPanel> panels = source.stream()
                .filter(settings::shows)
                .collect(ArrayList::new, List::add, List::addAll);
        if (panels.isEmpty()) return;
        panels.sort(Comparator.comparingInt(UiPanel::priority).reversed().thenComparing(UiPanel::id));

        float scale = settings.scale.factor;
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        int logicalWidth = Math.round(graphics.guiWidth() / scale);
        int logicalHeight = Math.round(graphics.guiHeight() / scale);

        // Один общий столбец должен выглядеть как цельный интерфейс, а не
        // набор несвязанных vanilla-scoreboard. Ширину определяет самый
        // широкий заголовок или непустая строка среди всех активных панелей.
        int sharedWidth = sharedWidth(font, panels);

        int y = TOP_MARGIN;
        int screenHeight = logicalHeight;
        for (int panelIndex = 0; panelIndex < panels.size(); panelIndex++) {
            UiPanel panel = panels.get(panelIndex);
            List<String> compactLines = panel.lines().stream().filter(line -> !line.isBlank()).toList();
            if (compactLines.isEmpty()) continue;

            // Всегда оставляем место хотя бы под заголовок и одну строку
            // следующих панелей. На небольшом GUI нижняя гильдия свернётся,
            // но не исчезнет целиком за ареной и пати.
            int panelsAfter = panels.size() - panelIndex - 1;
            int reserved = panelsAfter * (MIN_PANEL_HEIGHT + PANEL_GAP);
            int available = screenHeight - 6 - y - reserved;
            int maxLines = Math.max(1, (available - PAD_Y * 2 - 10) / 10);
            List<String> renderedLines = collapse(compactLines, maxLines);

            int width = sharedWidth;
            int height = PAD_Y * 2 + 10 + renderedLines.size() * 10;
            if (y + height > screenHeight - 6) break;
            int x = logicalWidth - RIGHT_MARGIN - width;

            int background = (settings.opacity.alpha << 24) | BG_RGB;
            graphics.fill(x, y, x + width, y + height, background);
            graphics.fill(x + width - 2, y, x + width, y + height, EDGE);
            graphics.text(font, legacy(panel.title(), TITLE), x + PAD_X, y + PAD_Y, TITLE, false);

            int lineY = y + PAD_Y + 11;
            for (String line : renderedLines) {
                graphics.text(font, legacy(line, TEXT), x + PAD_X, lineY, TEXT, false);
                lineY += 10;
            }
            y += height + PANEL_GAP;
        }
        graphics.pose().popMatrix();
    }

    private static List<String> collapse(List<String> lines, int maxLines) {
        if (lines.size() <= maxLines) return lines;
        if (maxLines == 1) return List.of("&8…");
        List<String> result = new ArrayList<>(lines.subList(0, maxLines));
        result.set(maxLines - 1, "&8…");
        return List.copyOf(result);
    }

    private static int preferredWidth(Font font, String title, List<String> lines) {
        int content = font.width(legacy(title, TITLE));
        for (String line : lines) content = Math.max(content, font.width(legacy(line, TEXT)));
        return Math.max(104, content + PAD_X * 2 + 2);
    }

    private static int sharedWidth(Font font, List<UiPanel> panels) {
        int width = 104;
        for (UiPanel panel : panels) {
            List<String> lines = panel.lines().stream().filter(line -> !line.isBlank()).toList();
            width = Math.max(width, preferredWidth(font, panel.title(), lines));
        }
        return Math.min(MAX_WIDTH, width);
    }

    /** Parses the legacy colours already used by the server plugins. */
    private static Component legacy(String input, int fallbackColor) {
        MutableComponent root = Component.empty();
        ChatFormatting active = null;
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if ((current == '&' || current == '\u00A7') && i + 1 < input.length()) {
                ChatFormatting next = ChatFormatting.getByCode(input.charAt(i + 1));
                if (next != null) {
                    append(root, part, active, fallbackColor);
                    active = next == ChatFormatting.RESET ? null : next;
                    i++;
                    continue;
                }
            }
            part.append(current);
        }
        append(root, part, active, fallbackColor);
        return root;
    }

    private static void append(MutableComponent root, StringBuilder text, ChatFormatting format, int fallback) {
        if (text.isEmpty()) return;
        MutableComponent component = Component.literal(text.toString());
        if (format == null) component = component.withColor(fallback);
        else component = component.withStyle(format);
        root.append(component);
        text.setLength(0);
    }
}
