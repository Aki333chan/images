package dev.addons.npc.service;

import dev.addons.npc.config.BuyerRepository;
import dev.addons.npc.model.BuyerDefinition;
import dev.addons.npc.model.BuyerOffer;
import dev.addons.npc.model.BuyerOffer.SaleMode;
import dev.addons.npc.model.BuyerOffer.SaleQuote;
import dev.addons.npc.model.TimedPercentage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.TransactionCategory;

public final class BuyerService implements Listener {
    private final JavaPlugin plugin;
    private final BuyerRepository repository;
    private final EconomyService economy;
    private final MessageService messages;
    private final NpcSagaRepository sagas;
    private final Set<UUID> transactions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public BuyerService(JavaPlugin plugin, BuyerRepository repository, EconomyService economy,
                        MessageService messages, NpcSagaRepository sagas) {
        this.plugin = plugin;
        this.repository = repository;
        this.economy = economy;
        this.messages = messages;
        this.sagas = sagas;
    }

    public void open(Player player, String buyerId) {
        if (!player.hasPermission("addonsnpc.buyer")) {
            messages.send(player, "no-permission");
            return;
        }
        BuyerDefinition buyer = repository.get(buyerId);
        if (buyer == null) {
            messages.send(player, "buyer-not-found", Map.of("buyer", buyerId));
            return;
        }
        BuyerHolder holder = new BuyerHolder(buyer.id());
        Inventory inventory = Bukkit.createInventory(holder, buyer.size(), MessageService.colorize(buyer.title()));
        holder.inventory = inventory;
        long now = System.currentTimeMillis();
        buyer.offers().values().forEach(offer -> {
            TimedPercentage bonus = activeBonus(buyer, offer, now);
            inventory.setItem(offer.slot(), icon(offer, bonus, now));
            holder.promotionKeys.put(offer.slot(), promotionKey(bonus, now));
        });
        player.openInventory(inventory);
    }

    private ItemStack icon(BuyerOffer offer, TimedPercentage bonus, long now) {
        ItemStack icon = offer.template(); icon.setAmount(1);
        ItemMeta meta = icon.getItemMeta();
        boolean activeBonus = bonus.active(now);
        meta.setDisplayName(MessageService.colorize(activeBonus
                ? messages.text("gui.buyer.bonus-name-prefix") + offer.displayName() : offer.displayName()));
        if (activeBonus) meta.setEnchantmentGlintOverride(true);
        Map<String, Object> values = placeholders(offer, 0, 0, bonus, now);
        List<String> lore = new ArrayList<>();
        if (offer.lore().isEmpty()) {
            lore.add(messages.text("gui.buyer.unit-price"));
            if (offer.bulkEnabled()) lore.add(messages.text("gui.buyer.bulk-price"));
            lore.add(messages.text(offer.matchMode() == BuyerOffer.MatchMode.EXACT
                    ? "gui.buyer.match-exact" : "gui.buyer.match-material"));
            lore.add("");
            lore.add(messages.text("gui.buyer.sell-one"));
            lore.add(messages.text("gui.buyer.sell-all-units"));
            if (offer.bulkEnabled()) {
                lore.add(messages.text("gui.buyer.sell-one-bulk"));
                lore.add(messages.text("gui.buyer.sell-all-bulk"));
            }
        } else {
            lore.addAll(offer.lore());
        }
        if (activeBonus) {
            lore.add("");
            lore.add(messages.text("gui.buyer.bonus"));
            lore.add(messages.text("gui.buyer.base-unit-price"));
            lore.add(messages.text("gui.buyer.bonus-unit-price"));
            if (offer.bulkEnabled()) lore.add(messages.text("gui.buyer.bonus-bulk-price"));
            lore.add(messages.text("gui.buyer.remaining"));
        }
        meta.setLore(lore.stream().map(line -> messages.format(line, values)).toList());
        icon.setItemMeta(meta);
        return icon;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BuyerHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getInventory().getSize()) return;
        SaleMode mode;
        if (event.isShiftClick()) {
            mode = event.isRightClick() ? SaleMode.BULK_ALL : SaleMode.UNIT_ALL;
        } else if (event.isRightClick()) {
            mode = SaleMode.BULK_ONE;
        } else if (event.isLeftClick()) {
            mode = SaleMode.UNIT_ONE;
        } else {
            return;
        }
        BuyerDefinition buyer = repository.get(holder.buyerId);
        BuyerOffer offer = buyer == null ? null : buyer.offers().get(event.getRawSlot());
        if (offer != null) {
            long now = System.currentTimeMillis();
            TimedPercentage bonus = activeBonus(buyer, offer, now);
            String promotionKey = promotionKey(bonus, now);
            if (!promotionKey.equals(holder.promotionKeys.get(offer.slot()))) {
                event.getInventory().setItem(offer.slot(), icon(offer, bonus, now));
                holder.promotionKeys.put(offer.slot(), promotionKey);
                messages.send(player, "buyer-bonus-changed");
                return;
            }
            sell(player, buyer, offer, mode, bonus, now);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof BuyerHolder) event.setCancelled(true);
    }

    private void sell(Player player, BuyerDefinition buyer, BuyerOffer offer,
                      SaleMode mode, TimedPercentage bonus, long now) {
        if (!transactions.add(player.getUniqueId())) return;
        if (!offer.permission().isBlank() && !player.hasPermission(offer.permission())) {
            transactions.remove(player.getUniqueId()); messages.send(player, "no-permission"); return;
        }
        if (!economy.available() && !economy.hook()) {
            transactions.remove(player.getUniqueId()); messages.send(player, "aurum-economy-unavailable"); return;
        }
        int available = countMatching(player.getInventory().getStorageContents(), offer);
        SaleQuote quoted = offer.quote(mode, available);
        if (quoted == null) {
            transactions.remove(player.getUniqueId());
            String key = (mode == SaleMode.BULK_ONE || mode == SaleMode.BULK_ALL) && !offer.bulkEnabled()
                    ? "buyer-bulk-disabled" : "buyer-not-enough-items";
            messages.send(player, key, placeholders(offer, available, 0, bonus, now)); return;
        }
        double basePayout = quoted.payout();
        SaleQuote finalQuote = new SaleQuote(quoted.amount(), bonus.bonus(basePayout, now));
        String operation = UUID.randomUUID().toString();
        Map<String, String> metadata = Map.of("plugin", "AddonsNPC", "operation", operation,
                "buyer", buyer.id(), "offer-slot", Integer.toString(offer.slot()));
        economy.reserve("npc-buyer:" + operation,
                new AccountId(AccountType.SYSTEM_SOURCE, "npc-buyers"), AccountId.player(player.getUniqueId()),
                finalQuote.payout(), TransactionCategory.NPC_SALE, "npc-buyer",
                buyer.id() + ":" + offer.slot(), metadata).whenComplete((result, error) -> runMain(() ->
                reservedSale(player, buyer.id(), offer.slot(), mode, finalQuote, basePayout, result, error)));
    }

    private void reservedSale(Player player, String buyerId, int slot, SaleMode mode, SaleQuote reserved,
                              double basePayout, HoldResult result, Throwable error) {
        if (error != null || result == null || (result.status() != HoldResult.Status.SUCCESS
                && result.status() != HoldResult.Status.DUPLICATE) || result.hold().isEmpty()) {
            transactions.remove(player.getUniqueId()); messages.send(player, "buyer-sale-failed"); return;
        }
        NpcSaga saga;
        try { saga = sagas.begin(NpcSaga.Kind.BUYER_SALE, player.getUniqueId(), result.hold().orElseThrow()); }
        catch (RuntimeException failure) {
            economy.release(NpcSaga.held(NpcSaga.Kind.BUYER_SALE, player.getUniqueId(), result.hold().orElseThrow()));
            transactions.remove(player.getUniqueId()); messages.send(player, "buyer-sale-failed"); return;
        }
        BuyerDefinition buyer = repository.get(buyerId);
        BuyerOffer offer = buyer == null ? null : buyer.offers().get(slot);
        long now = System.currentTimeMillis();
        TimedPercentage bonus = offer == null ? TimedPercentage.none() : activeBonus(buyer, offer, now);
        ItemStack[] contents = player.getInventory().getStorageContents();
        int available = offer == null ? 0 : countMatching(contents, offer);
        SaleQuote current = offer == null ? null : offer.quote(mode, available);
        if (current != null) current = new SaleQuote(current.amount(), bonus.bonus(current.payout(), now));
        if (current == null || current.amount() != reserved.amount()
                || economy.amount(current.payout()).compareTo(economy.amount(reserved.payout())) != 0) {
            economy.release(saga).whenComplete((ignored, failure) -> sagas.complete(saga));
            transactions.remove(player.getUniqueId()); messages.send(player, "buyer-sale-failed"); return;
        }
        ItemStack[] snapshot = cloneContents(contents);
        if (removeMatching(contents, offer, current.amount()) != current.amount()) {
            economy.release(saga).whenComplete((ignored, failure) -> sagas.complete(saga));
            transactions.remove(player.getUniqueId()); messages.send(player, "buyer-sale-failed"); return;
        }
        List<ItemStack> removedItems = removedItems(snapshot, contents);
        player.getInventory().setStorageContents(contents);
        NpcSaga applied;
        try { applied = sagas.markApplied(saga); }
        catch (RuntimeException failure) {
            restoreItems(player, removedItems); economy.release(saga);
            transactions.remove(player.getUniqueId()); messages.send(player, "buyer-sale-failed"); return;
        }
        Map<String, Object> values = new HashMap<>(placeholders(offer, current.amount(), current.payout(), bonus, now));
        values.put("base_price", economy.format(basePayout)); values.put("player", player.getName());
        economy.capture(applied).whenComplete((capture, failure) -> runMain(() -> {
            transactions.remove(player.getUniqueId());
            if (failure == null && capture != null && (capture.status() == HoldResult.Status.SUCCESS
                    || capture.status() == HoldResult.Status.DUPLICATE)) {
                sagas.complete(applied); values.put("balance", economy.format(economy.balance(player)));
                for (String command : offer.commands()) try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(MessageService.replace(command, values)));
                } catch (RuntimeException commandError) {
                    plugin.getLogger().warning("Buyer post-sale command failed: " + commandError.getMessage());
                }
                player.updateInventory(); messages.send(player, "buyer-sale-success", values);
            } else if (capture != null && capture.status() != HoldResult.Status.UNAVAILABLE) {
                restoreItems(player, removedItems); economy.release(applied); sagas.complete(applied);
                player.updateInventory(); messages.send(player, "buyer-sale-failed");
            } else {
                plugin.getLogger().warning("NPC buyer sale " + applied.id() + " is awaiting AurumCore recovery");
                messages.send(player, "buyer-sale-failed");
            }
        }));
    }

    private static List<ItemStack> removedItems(ItemStack[] before, ItemStack[] after) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < before.length; slot++) {
            ItemStack original = before[slot];
            if (original == null) continue;
            ItemStack remaining = after[slot];
            int amount = original.getAmount() - (remaining != null && remaining.isSimilar(original)
                    ? remaining.getAmount() : 0);
            if (amount > 0) {
                ItemStack removed = original.clone();
                removed.setAmount(amount);
                result.add(removed);
            }
        }
        return result;
    }

    private void restoreItems(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            for (ItemStack leftover : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
        player.updateInventory();
    }

    private void runMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run(); else Bukkit.getScheduler().runTask(plugin, action);
    }

    private Map<String, Object> placeholders(BuyerOffer offer, int amount, double payout,
                                             TimedPercentage bonus, long now) {
        return Map.of(
                "item", offer.template().getType().name().toLowerCase(),
                "amount", amount,
                "price", economy.format(payout),
                "unit_price", economy.format(bonus.bonus(offer.unitPrice(), now)),
                "base_unit_price", economy.format(offer.unitPrice()),
                "bulk_amount", offer.bulkAmount(),
                "bulk_price", economy.format(bonus.bonus(offer.bulkPrice(), now)),
                "base_bulk_price", economy.format(offer.bulkPrice()),
                "bonus_percent", percentage(bonus.percent()),
                "bonus_remaining", messages.remaining(bonus, now));
    }

    private static TimedPercentage activeBonus(BuyerDefinition buyer, BuyerOffer offer, long now) {
        return offer.bonus().active(now) ? offer.bonus() : buyer.bonus();
    }

    private static String promotionKey(TimedPercentage bonus, long now) {
        return bonus.active(now) ? bonus.percent() + ":" + bonus.expiresAtMillis() : "none";
    }

    private static String percentage(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    static int countMatching(ItemStack[] contents, BuyerOffer offer) {
        int count = 0;
        for (ItemStack stack : contents) if (offer.matches(stack)) count += stack.getAmount();
        return count;
    }

    static int removeMatching(ItemStack[] contents, BuyerOffer offer, int requested) {
        int remaining = requested;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!offer.matches(stack)) continue;
            int taken = Math.min(remaining, stack.getAmount());
            remaining -= taken;
            if (taken == stack.getAmount()) contents[slot] = null;
            else stack.setAmount(stack.getAmount() - taken);
        }
        return requested - remaining;
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] result = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            result[index] = contents[index] == null ? null : contents[index].clone();
        }
        return result;
    }

    private static String stripSlash(String command) { return command.startsWith("/") ? command.substring(1) : command; }

    private static final class BuyerHolder implements InventoryHolder {
        private final String buyerId;
        private final Map<Integer, String> promotionKeys = new HashMap<>();
        private Inventory inventory;
        private BuyerHolder(String buyerId) { this.buyerId = buyerId; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
