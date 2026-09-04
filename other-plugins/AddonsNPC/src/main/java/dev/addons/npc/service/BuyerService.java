package dev.addons.npc.service;

import dev.addons.npc.config.BuyerRepository;
import dev.addons.npc.model.BuyerDefinition;
import dev.addons.npc.model.BuyerOffer;
import dev.addons.npc.model.BuyerOffer.SaleMode;
import dev.addons.npc.model.BuyerOffer.SaleQuote;
import dev.addons.npc.model.TimedPercentage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

public final class BuyerService implements Listener {
    private final JavaPlugin plugin;
    private final BuyerRepository repository;
    private final EconomyService economy;
    private final MessageService messages;
    private final Set<UUID> transactions = new HashSet<>();

    public BuyerService(JavaPlugin plugin, BuyerRepository repository, EconomyService economy, MessageService messages) {
        this.plugin = plugin;
        this.repository = repository;
        this.economy = economy;
        this.messages = messages;
    }

    public void open(Player player, String buyerId) {
        if (!player.hasPermission("addonsnpc.buyer")) {
            messages.send(player, "no-permission");
            return;
        }
        BuyerDefinition buyer = repository.get(buyerId);
        if (buyer == null) {
            messages.raw(player, messages.prefix() + "&cBuyer '&f" + buyerId + "&c' was not found.", Map.of());
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
                ? "&b&lБОНУС &r" + offer.displayName() : offer.displayName()));
        if (activeBonus) meta.setEnchantmentGlintOverride(true);
        Map<String, Object> values = placeholders(offer, 0, 0, bonus, now);
        List<String> lore = new ArrayList<>();
        if (offer.lore().isEmpty()) {
            lore.add("&7За штуку: &e{unit_price}");
            if (offer.bulkEnabled()) lore.add("&7Оптом: &f{bulk_amount} шт. &7за &e{bulk_price}");
            lore.add(offer.matchMode() == BuyerOffer.MatchMode.EXACT ? "&8Учитываются все свойства предмета" : "&8Учитывается только материал");
            lore.add("");
            lore.add("&aЛКМ &7— продать 1");
            lore.add("&aShift+ЛКМ &7— продать всё поштучно");
            if (offer.bulkEnabled()) {
                lore.add("&bПКМ &7— продать 1 оптовую партию");
                lore.add("&bShift+ПКМ &7— продать все полные партии");
            }
        } else {
            lore.addAll(offer.lore());
        }
        if (activeBonus) {
            lore.add("");
            lore.add("&b&lБОНУС +{bonus_percent}%");
            lore.add("&8&mОбычная выплата за штуку: {base_unit_price}");
            lore.add("&6Повышенная выплата: &e&l{unit_price}");
            if (offer.bulkEnabled()) lore.add("&6Оптовая выплата: &e&l{bulk_price}");
            lore.add("&7Осталось: &f{bonus_remaining}");
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
            sell(player, offer, mode, bonus, now);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof BuyerHolder) event.setCancelled(true);
    }

    private void sell(Player player, BuyerOffer offer, SaleMode mode, TimedPercentage bonus, long now) {
        if (!transactions.add(player.getUniqueId())) return;
        try {
            if (!offer.permission().isBlank() && !player.hasPermission(offer.permission())) {
                messages.send(player, "no-permission"); return;
            }
            if (!economy.available()) {
                messages.send(player, "vault-unavailable"); return;
            }
            ItemStack[] contents = player.getInventory().getStorageContents();
            int available = countMatching(contents, offer);
            SaleQuote quote = offer.quote(mode, available);
            if (quote == null) {
                String key = (mode == SaleMode.BULK_ONE || mode == SaleMode.BULK_ALL) && !offer.bulkEnabled()
                        ? "buyer-bulk-disabled" : "buyer-not-enough-items";
                messages.send(player, key, placeholders(offer, available, 0, bonus, now));
                return;
            }
            double basePayout = quote.payout();
            quote = new SaleQuote(quote.amount(), bonus.bonus(basePayout, now));
            ItemStack[] snapshot = cloneContents(contents);
            if (removeMatching(contents, offer, quote.amount()) != quote.amount()) {
                player.getInventory().setStorageContents(snapshot);
                messages.send(player, "buyer-sale-failed"); return;
            }
            player.getInventory().setStorageContents(contents);
            Optional<String> failure;
            try {
                failure = economy.deposit(player, quote.payout());
            } catch (RuntimeException exception) {
                failure = Optional.of("Vault provider threw " + exception.getClass().getSimpleName());
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Vault sale threw an exception for " + player.getName(), exception);
            }
            if (failure.isPresent()) {
                player.getInventory().setStorageContents(snapshot);
                player.updateInventory();
                plugin.getLogger().warning("Vault sale failed for " + player.getName() + ": " + failure.get());
                messages.send(player, "buyer-sale-failed"); return;
            }
            Map<String, Object> values = new HashMap<>(placeholders(offer, quote.amount(), quote.payout(), bonus, now));
            values.put("base_price", economy.format(basePayout));
            values.put("player", player.getName());
            values.put("balance", economy.format(economy.balance(player)));
            for (String command : offer.commands()) {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(MessageService.replace(command, values)));
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Buyer post-sale command failed: " + exception.getMessage());
                }
            }
            player.updateInventory();
            messages.send(player, "buyer-sale-success", values);
        } finally {
            transactions.remove(player.getUniqueId());
        }
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
                "bonus_remaining", bonus.remaining(now));
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
