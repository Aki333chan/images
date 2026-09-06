package ovh.aurumgg.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small reusable form used for numeric values, promotions and offer fields. */
final class AurumFormScreen extends Screen {
    record Field(String name, String translation, String value, int maxLength) {}
    private final Screen parent;
    private final List<Field> fields;
    private final Consumer<Map<String, String>> save;
    private final Map<String, EditBox> inputs = new LinkedHashMap<>();

    AurumFormScreen(Screen parent, Component title, List<Field> fields, Consumer<Map<String, String>> save) {
        super(title); this.parent = parent; this.fields = fields; this.save = save;
    }

    @Override protected void init() {
        inputs.clear();
        int columns = columns();
        int rows = (fields.size() + columns - 1) / columns;
        int formWidth = Math.min(columns == 2 ? 420 : 320, width - 20);
        int fieldWidth = (formWidth - (columns - 1) * 6) / columns;
        int x = (width - formWidth) / 2;
        int top = Math.max(34, (height - rows * 30 - 42) / 2);
        for (int index = 0; index < fields.size(); index++) {
            Field field = fields.get(index);
            int column = index % columns;
            int row = index / columns;
            EditBox input = new EditBox(font, x + column * (fieldWidth + 6), top + row * 30 + 10, fieldWidth, 20,
                    Component.translatable(field.translation));
            input.setMaxLength(field.maxLength);
            input.setValue(field.value == null ? "" : field.value);
            addRenderableWidget(input);
            inputs.put(field.name, input);
        }
        int buttonsY = top + rows * 30 + 6;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), ignored -> minecraft.gui.setScreen(parent))
                .bounds(x, buttonsY, (formWidth - 6) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.aurumui.action.save"), ignored -> {
            Map<String, String> values = new LinkedHashMap<>();
            inputs.forEach((name, input) -> values.put(name, input.getValue().trim()));
            minecraft.gui.setScreen(parent);
            save.accept(Map.copyOf(values));
        }).bounds(x + (formWidth + 6) / 2, buttonsY, (formWidth - 6) / 2, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 18, 0xFFFFC85C);
        int columns = columns();
        int rows = (fields.size() + columns - 1) / columns;
        int formWidth = Math.min(columns == 2 ? 420 : 320, width - 20);
        int fieldWidth = (formWidth - (columns - 1) * 6) / columns;
        int top = Math.max(34, (height - rows * 30 - 42) / 2);
        int x = (width - formWidth) / 2;
        for (int index = 0; index < fields.size(); index++) {
            int column = index % columns;
            int row = index / columns;
            String label = font.plainSubstrByWidth(Component.translatable(fields.get(index).translation).getString(), fieldWidth);
            graphics.text(font, Component.literal(label), x + column * (fieldWidth + 6),
                    top + row * 30, 0xFFAAAAAA, false);
        }
    }

    private int columns() { return fields.size() > 3 && width >= 300 ? 2 : 1; }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
