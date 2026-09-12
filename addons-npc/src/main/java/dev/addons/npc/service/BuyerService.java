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
import ovh.aurumgg.core.api.ClaimRequest;

public final class BuyerService implements Listener {
    private final JavaPlugin plugin;
    private final BuyerRepository repository;
    private final EconomyService economy;
    private final MessageService messages;
    private final ClaimDelivery delivery;
    private final Set<UUID> transactions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public BuyerService(JavaPlugin plugin, BuyerRepository repository, EconomyService economy,
                        MessageService messages, ClaimDelivery delivery) {
        this.plugin = plugin;
        this.repository = repository;
        this.economy = economy;
        this.messages = messages;
        this.delivery = delivery;
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

    /**
     * Записать долг, а потом забрать предметы — и никогда наоборот.
     *
     * <h2>Что здесь было не так</h2>
     *
     * Прежний порядок был: зарезервировать выплату, забрать предметы, отметить
     * журнал, списать. Авария между «забрали» и «отметили» оставляла резерв,
     * который перезапущенный плагин считал неприменённым: он освобождал деньги,
     * а предметы игрока уже были забраны. Терялось имущество ИГРОКА, и
     * восстановить его было не из чего.
     *
     * <h2>Почему резерв вообще исчез</h2>
     *
     * Резерв доказывал, что деньги есть. Доказывать нечего: скупщик платит из
     * {@code SYSTEM_SOURCE:npc-buyers}, а системный источник в ledger считается
     * обеспеченным всегда. На деле резерв работал обещанием — и плохим, потому
     * что обещание с TTL истекает ровно тогда, когда сервер лежит.
     *
     * Заявка — это то обещание, которым резерв притворялся, и она не истекает.
     * Поэтому holds здесь больше нет, выплата стала обычной идемпотентной
     * проводкой, а порядок «сначала запись, потом предметы» держит заявка.
     */
    private void sell(Player player, BuyerDefinition buyer, BuyerOffer offer,
                      SaleMode mode, TimedPercentage bonus, long now) {
        if (!transactions.add(player.getUniqueId())) return;
        if (!offer.permission().isBlank() && !player.hasPermission(offer.permission())) {
            transactions.remove(player.getUniqueId()); messages.send(player, "no-permission"); return;
        }
        if (!economy.available() && !economy.hook()) {
            transactions.remove(player.getUniqueId()); messages.send(player, "aurum-economy-unavailable"); return;
        }
        if (!delivery.available()) {
            // Записать долг некуда. Забрать предметы и понадеяться — ровно то,
            // от чего заявки и избавляют.
            transactions.remove(player.getUniqueId()); messages.send(player, "delivery-unavailable"); return;
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
        java.math.BigDecimal payout = economy.amount(finalQuote.payout());
        if (payout.signum() <= 0) {
            transactions.remove(player.getUniqueId()); messages.send(player, "buyer-sale-failed"); return;
        }

        Map<String, Object> values = new HashMap<>(placeholders(offer, finalQuote.amount(),
                finalQuote.payout(), bonus, now));
        values.put("base_price", economy.format(basePayout));
        values.put("player", player.getName());
        values.put("balance", economy.format(economy.balance(player)));
        String operation = UUID.randomUUID().toString();
        List<ClaimCommand> commands;
        try {
            commands = ClaimCommand.prepareAll(offer.commands().stream()
                    .map(command -> MessageService.replace(command, values)).toList(),
                    "npc-buyer-command:" + operation);
        } catch (IllegalArgumentException invalidCommand) {
            plugin.getLogger().warning("Invalid buyer claim command: " + invalidCommand.getMessage());
            transactions.remove(player.getUniqueId());
            messages.send(player, "buyer-sale-failed");
            return;
        }
        SaleClaim sale = new SaleClaim(operation, buyer.id(), offer.slot(), finalQuote.amount(),
                payout, now + saleDeadlineMillis(), commands);
        ClaimRequest request;
        try {
            request = new ClaimRequest("npc-buyer-claim:" + operation, ClaimGateway.PLUGIN,
                    player.getUniqueId(), BuyerPlan.KIND, sale.stepCount(), sale.summary(), sale.encode());
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().warning("Could not describe NPC sale as a claim: " + invalid.getMessage());
            transactions.remove(player.getUniqueId()); messages.send(player, "buyer-sale-failed"); return;
        }

        delivery.promise(request).whenComplete((promised, failure) -> runMain(() -> {
            transactions.remove(player.getUniqueId());
            if (failure != null || promised == null || !promised.ok() || promised.claim().isEmpty()) {
                // Ничего не записано и ничего не забрано: продажи не было.
                messages.send(player, "buyer-sale-failed");
                return;
            }
            messages.send(player, "buyer-sale-success", values);
            // Дальше всё держит запись в Core: даже если сервер умрёт на
            // следующей строке, предметы и выплата сойдутся при следующем входе.
            delivery.deliver(player, promised.claim().orElseThrow(),
                    new BuyerPlan(plugin, economy, messages, repository, delivery, sale));
        }));
    }

    /**
     * Сколько заявка на продажу остаётся годной.
     *
     * Это защита игрока, а не денег: продажа, нажатая перед аварией и
     * возобновлённая через неделю, забрала бы предметы, которые человек давно
     * решил оставить себе.
     */
    private long saleDeadlineMillis() {
        long configured = plugin.getConfig().getLong("economy.sale-deadline-seconds", 900L);
        return Math.max(60L, Math.min(86_400L, configured)) * 1000L;
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

    private static final class BuyerHolder implements InventoryHolder {
        private final String buyerId;
        private final Map<Integer, String> promotionKeys = new HashMap<>();
        private Inventory inventory;
        private BuyerHolder(String buyerId) { this.buyerId = buyerId; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
