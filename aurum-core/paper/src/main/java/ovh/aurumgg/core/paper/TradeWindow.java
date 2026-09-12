package ovh.aurumgg.core.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ovh.aurumgg.core.api.TradeOffer;
import ovh.aurumgg.core.api.TradeSession;
import ovh.aurumgg.core.api.TradeState;

/**
 * The trade window — a view of the table, and nothing more.
 *
 * <h2>Why the window holds no real items</h2>
 *
 * Everything in these slots is a display copy. The actual goods left the
 * player's inventory when they were offered and exist only as the durable
 * record in Core. A window that held the real stacks would be a second place
 * that can disagree with the first, and the moment those two disagree, an item
 * has been duplicated.
 *
 * So every click is cancelled and turned into a call on {@link TradeCoordinator}.
 * The rules live there; this file decides only what things look like.
 *
 * <h2>The confirm button sends the revision it was drawn with</h2>
 *
 * Not the current one — the one the player was looking at when they clicked. If
 * the table changed in between, Core refuses, and the refusal is the feature:
 * it is what stops the goods being swapped between the look and the click.
 *
 * <h2>Closing the window calls the trade off</h2>
 *
 * There are other people's goods on the table. Walking away from it silently
 * would leave them stranded until the session timeout, so the close IS the
 * cancellation, and cancelling gives both tables back.
 */
final class TradeWindow implements Listener {

    /** Item slots per side. The command path caps the table at the same number. */
    static final int SIDE_SLOTS = 16;

    private static final int[] LEFT = {0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30};
    private static final int[] RIGHT = {5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35};
    private static final int[] DIVIDER = {4, 13, 22, 31, 40, 49};
    private static final int LEFT_MONEY = 38;
    private static final int RIGHT_MONEY = 42;
    private static final int LEFT_CONFIRM = 47;
    private static final int RIGHT_CONFIRM = 51;

    private final AurumCorePlugin plugin;
    private final TradeCoordinator trades;
    /** One shared inventory per trade: both players look at the same object. */
    private final Map<UUID, Holder> open = new ConcurrentHashMap<>();

    TradeWindow(AurumCorePlugin plugin, TradeCoordinator trades) {
        this.plugin = plugin;
        this.trades = trades;
        trades.notifyChanges(this::redraw);
    }

    /** Open the window on the player's current trade. */
    void show(Player player) {
        trades.current(player, (trade, offers) -> openFor(trade, offers, List.of(player)));
    }

    /**
     * Follow the table.
     *
     * <p>The first change on a trade that has no window yet IS the trade
     * opening, so the window appears for both players at once rather than
     * waiting for each of them to ask for it.
     */
    private void redraw(TradeSession trade) {
        Holder holder = open.get(trade.id());
        if (holder == null) {
            if (trade.state() == TradeState.OPEN) {
                trades.refresh(trade, (current, offers) -> openFor(current, offers, both(current)));
            }
            return;
        }
        if (trade.state().finished()) {
            // Settled, cancelled or timed out: the table no longer exists, and
            // leaving a stale picture of it open invites a click on nothing.
            closeAll(holder);
            return;
        }
        trades.refresh(trade, (current, offers) -> {
            holder.trade = current;
            draw(holder, current, offers);
        });
    }

    private void openFor(TradeSession trade, List<TradeOffer> offers, List<Player> viewers) {
        Holder holder = open.computeIfAbsent(trade.id(), id -> new Holder(trade));
        if (holder.inventory == null) {
            holder.inventory = Bukkit.createInventory(holder, 54,
                    plugin.messages().label("trade-window-title", Map.of()));
        }
        holder.trade = trade;
        draw(holder, trade, offers);
        for (Player viewer : viewers) {
            if (viewer.isOnline() && !holder.inventory.getViewers().contains(viewer)) {
                viewer.openInventory(holder.inventory);
            }
        }
    }

    private static List<Player> both(TradeSession trade) {
        List<Player> viewers = new ArrayList<>();
        for (UUID side : List.of(trade.first(), trade.second())) {
            Player online = Bukkit.getPlayer(side);
            if (online != null) viewers.add(online);
        }
        return viewers;
    }

    // ------------------------------------------------------------ отрисовка

    private void draw(Holder holder, TradeSession trade, List<TradeOffer> offers) {
        Inventory inventory = holder.inventory;
        if (inventory == null) return;
        inventory.clear();
        holder.revision = trade.revision();
        holder.left = items(trade.first(), offers);
        holder.right = items(trade.second(), offers);

        for (int slot : DIVIDER) inventory.setItem(slot, pane(Material.GRAY_STAINED_GLASS_PANE));
        place(inventory, LEFT, holder.left);
        place(inventory, RIGHT, holder.right);
        inventory.setItem(LEFT_MONEY, money(trade.first(), offers));
        inventory.setItem(RIGHT_MONEY, money(trade.second(), offers));
        inventory.setItem(LEFT_CONFIRM, confirm(trade, trade.first()));
        inventory.setItem(RIGHT_CONFIRM, confirm(trade, trade.second()));
    }

    private static void place(Inventory inventory, int[] slots, List<ItemStack> items) {
        for (int index = 0; index < slots.length; index++) {
            // A copy, always. Handing the window the real stack is how a display
            // slot becomes a second owner of the same item.
            inventory.setItem(slots[index], index < items.size() ? items.get(index).clone() : null);
        }
    }

    private ItemStack money(UUID owner, List<TradeOffer> offers) {
        TradeOffer offer = offers.stream().filter(it -> it.owner().equals(owner)).findFirst().orElse(null);
        String amount = offer != null && offer.hasMoney()
                ? offer.money().stripTrailingZeros().toPlainString() + " "
                        + plugin.settings().currency().symbol()
                : "-";
        return labelled(Material.GOLD_NUGGET,
                plugin.messages().label("trade-window-money",
                        Map.of("player", name(owner), "amount", amount)),
                plugin.messages().label("trade-window-money-hint", Map.of()));
    }

    private ItemStack confirm(TradeSession trade, UUID owner) {
        boolean done = trade.confirmed(owner);
        return labelled(done ? Material.LIME_DYE : Material.GRAY_DYE,
                plugin.messages().label(done ? "trade-window-confirmed" : "trade-window-confirm",
                        Map.of("player", name(owner))),
                plugin.messages().label("trade-window-confirm-hint", Map.of()));
    }

    // --------------------------------------------------------------- клики

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        // Cancelled FIRST, before any check. These are real ItemStacks in the
        // slots; an uncancelled click walks out of the window with a copy of
        // something that belongs to the other player.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) return;

        TradeSession trade = holder.trade;
        if (trade == null || !trade.involves(player.getUniqueId())) return;
        boolean isFirst = trade.first().equals(player.getUniqueId());
        int[] mine = isFirst ? LEFT : RIGHT;
        int confirmSlot = isFirst ? LEFT_CONFIRM : RIGHT_CONFIRM;
        int raw = event.getRawSlot();

        if (raw < 0 || raw >= event.getInventory().getSize()) {
            // Clicked their own inventory: offer what they clicked.
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir()) offer(player, clicked, event);
            return;
        }
        if (raw == confirmSlot) {
            confirm(player, holder);
            return;
        }
        int index = indexOf(mine, raw);
        if (index >= 0) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                offer(player, cursor, event);
            } else {
                takeBack(player, index);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        if (holder.closing) return;
        TradeSession trade = holder.trade;
        if (trade == null || trade.state().finished()) return;
        // Walking away from a table with someone else's goods on it is a
        // cancellation, not a pause. Cancelling gives both tables back.
        trades.cancel(trade, "window closed");
    }

    // ------------------------------------------------------------ действия

    /**
     * Offer what the player clicked, exactly that.
     *
     * <p>The stack is handed to the coordinator by value and removed from the
     * player only once the offer is ready to be written. Routing this through
     * the main hand, as the command does, would mean re-reading the hand after
     * an asynchronous hop — and offering whatever ended up there by then.
     */
    private void offer(Player player, ItemStack item, InventoryClickEvent event) {
        ItemStack offered = item.clone();
        boolean fromCursor = event.getCursor() != null && !event.getCursor().getType().isAir();
        trades.current(player, (trade, offers) -> trades.putItem(player, trade, offers, offered, () -> {
            if (fromCursor) event.getView().setCursor(null);
            else player.getInventory().removeItem(offered);
        }));
    }

    private void takeBack(Player player, int index) {
        trades.current(player, (trade, offers) -> trades.takeBack(player, trade, offers, index));
    }

    /** Confirm the revision this window was drawn with, not whatever is current. */
    private void confirm(Player player, Holder holder) {
        TradeSession drawn = holder.trade;
        if (drawn == null) return;
        trades.confirmDrawn(player, drawn.id(), holder.revision);
    }

    // ------------------------------------------------------------ служебное

    private void closeAll(Holder holder) {
        holder.closing = true;
        open.remove(holder.tradeId);
        if (holder.inventory != null) {
            new ArrayList<>(holder.inventory.getViewers())
                    .forEach(viewer -> viewer.closeInventory());
        }
    }

    private static int indexOf(int[] slots, int raw) {
        for (int index = 0; index < slots.length; index++) if (slots[index] == raw) return index;
        return -1;
    }

    private static List<ItemStack> items(UUID owner, List<TradeOffer> offers) {
        return offers.stream()
                .filter(offer -> offer.owner().equals(owner))
                .findFirst()
                .flatMap(offer -> TradeItems.decode(offer.items(), offer.itemsFormatVersion()))
                .orElse(List.of());
    }

    private static ItemStack pane(Material material) {
        return labelled(material, net.kyori.adventure.text.Component.empty(), null);
    }

    private static ItemStack labelled(Material material, net.kyori.adventure.text.Component name,
                                      net.kyori.adventure.text.Component hint) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (hint != null) meta.lore(List.of(hint));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String name(UUID player) {
        String known = Bukkit.getOfflinePlayer(player).getName();
        return known == null ? player.toString() : known;
    }

    /** One table, one inventory, both players. */
    private static final class Holder implements InventoryHolder {
        private final UUID tradeId;
        private volatile TradeSession trade;
        private volatile Inventory inventory;
        private volatile long revision;
        private volatile boolean closing;
        private List<ItemStack> left = List.of();
        private List<ItemStack> right = List.of();

        private Holder(TradeSession trade) {
            this.tradeId = trade.id();
            this.trade = trade;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
