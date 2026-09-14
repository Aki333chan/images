package ovh.aurumgg.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.ScrollWheelHandler;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;

/** Lets an Aurum screen select the hand item without closing the screen. */
final class AurumHotbarScroll {
    private static final ScrollWheelHandler WHEEL = new ScrollWheelHandler();

    private AurumHotbarScroll() {}

    static boolean handle(Minecraft minecraft, double horizontal, double vertical) {
        if (minecraft.player == null) return false;
        var scroll = WHEEL.onMouseScroll(horizontal, vertical);
        if (scroll.x == 0 && scroll.y == 0) return true;
        int amount = scroll.y == 0 ? -scroll.x : scroll.y;
        Inventory inventory = minecraft.player.getInventory();
        int next = ScrollWheelHandler.getNextScrollWheelSelection(
                amount, inventory.getSelectedSlot(), Inventory.getSelectionSize());
        if (next == inventory.getSelectedSlot()) return true;
        inventory.setSelectedSlot(next);
        // Normally MultiPlayerGameMode synchronises this on its next tick. Send
        // immediately as well so clicking "from hand" in the same frame cannot
        // use the previously selected server-side item.
        var connection = minecraft.getConnection();
        if (connection != null) connection.send(new ServerboundSetCarriedItemPacket(next));
        return true;
    }
}
