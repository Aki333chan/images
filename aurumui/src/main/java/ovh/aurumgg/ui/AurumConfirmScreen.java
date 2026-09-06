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
        int y = height / 2 + 10;
        addRenderableWidget(Button.builder(Component.translatable("gui.no"), ignored -> minecraft.gui.setScreen(parent))
                .bounds(x, y, each, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.yes"), ignored -> {
            minecraft.gui.setScreen(parent); accepted.run();
        }).bounds(x + each + 6, y, each, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, height / 2 - 18, 0xFFFFC85C);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
