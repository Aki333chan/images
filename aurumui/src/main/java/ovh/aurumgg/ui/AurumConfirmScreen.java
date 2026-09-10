package ovh.aurumgg.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class AurumConfirmScreen extends Screen {
    private final Screen parent;
    private final Runnable accepted;

    AurumConfirmScreen(Screen parent, Component question, Runnable accepted) {
        super(question); this.parent = parent; this.accepted = accepted;
    }

    @Override protected void init() {
        int total = Math.min(276, width - 20);
        int x = (width - total) / 2;
        int each = (total - 6) / 2;
        int y = Math.min(height - 26, height / 2 + 36);
        addRenderableWidget(Button.builder(Component.translatable("gui.no"), ignored -> minecraft.gui.setScreen(parent))
                .bounds(x, y, each, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.yes"), ignored -> {
            minecraft.gui.setScreen(parent); accepted.run();
        }).bounds(x + each + 6, y, each, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        var lines = font.split(title, Math.min(400, width - 30));
        int y = Math.max(12, height / 2 - lines.size() * 10);
        for (var line : lines) {
            graphics.text(font, line, (width - font.width(line)) / 2, y, 0xFFFFC85C, false);
            y += 10;
        }
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
