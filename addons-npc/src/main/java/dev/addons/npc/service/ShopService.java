package dev.addons.npc.service;

import dev.addons.npc.config.ShopRepository;
import dev.addons.npc.model.ShopDefinition;
import dev.addons.npc.model.ShopOffer;
import dev.addons.npc.model.TimedPercentage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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

public final class ShopService implements Listener {
    private final JavaPlugin plugin;
    private final ShopRepository repository;
    private final EconomyService economy;
    private final MessageService messages;
    private final NpcSagaRepository sagas;
    private final Set<UUID> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ShopService(JavaPlugin plugin, ShopRepository repository, EconomyService economy,
                       MessageService messages, NpcSagaRepository sagas) {
        this.plugin = plugin;
        this.repository = repository;
        this.economy = economy;
        this.messages = messages;
        this.sagas = sagas;
    }

    public void open(Player player, String shopId) {
        if (!player.hasPermission("addonsnpc.shop")) {
            messages.send(player, "no-permission");
            return;
        }
        ShopDefinition shop = repository.get(shopId);
        if (shop == null) {
            messages.send(player, "shop-not-found", Map.of("shop", shopId));
            return;
        }
        ShopHolder holder = new ShopHolder(shop.id());
        Inventory inventory = Bukkit.createInventory(holder, shop.size(), MessageService.colorize(shop.title()));
        holder.inventory = inventory;
        long now = System.currentTimeMillis();
        for (ShopOffer offer : shop.offers().values()) {
            ActivePrice price = activePrice(shop, offer, now);
            inventory.setItem(offer.slot(), icon(offer, price, now));
            holder.shownPrices.put(offer.slot(), price.finalPrice());
            holder.promotionKeys.put(offer.slot(), promotionKey(price));
        }
        player.openInventory(inventory);
    }

    private ItemStack icon(ShopOffer offer, ActivePrice activePrice, long now) {
        ItemStack icon = offer.icon() == offer.item() ? offer.product() : new ItemStack(offer.icon());
        icon.setAmount(1);
        ItemMeta meta = icon.getItemMeta();
        meta.setDisplayName(MessageService.colorize(activePrice.discounted()
                ? messages.text("gui.shop.discount-name-prefix") + offer.displayName() : offer.displayName()));
        if (activePrice.discounted()) meta.setEnchantmentGlintOverride(true);
        List<String> lore = new ArrayList<>();
        Map<String, Object> values = pricePlaceholders(offer, activePrice, now);
        if (offer.lore().isEmpty()) {
            lore.add(messages.text("gui.shop.receive"));
            if (!activePrice.discounted()) lore.add(messages.text("gui.shop.price"));
            lore.add(messages.text("gui.shop.stock"));
        } else {
            lore.addAll(offer.lore());
        }
        if (activePrice.discounted()) {
            lore.add("");
            lore.add(messages.text("gui.shop.discount"));
            lore.add(messages.text("gui.shop.base-price"));
            lore.add(messages.text("gui.shop.sale-price"));
            lore.add(messages.text("gui.shop.remaining"));
        }
        meta.setLore(lore.stream().map(line -> messages.format(line, values)).toList());
        icon.setItemMeta(meta);
        return icon;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getInventory().getSize()) {
            return;
        }
        ShopDefinition shop = repository.get(holder.shopId);
        ShopOffer offer = shop == null ? null : shop.offers().get(event.getRawSlot());
        if (offer != null) {
            long now = System.currentTimeMillis();
            ActivePrice price = activePrice(shop, offer, now);
            Double shownPrice = holder.shownPrices.get(offer.slot());
            if (shownPrice == null || Math.abs(shownPrice - price.finalPrice()) > 0.0000001
                    || !promotionKey(price).equals(holder.promotionKeys.get(offer.slot()))) {
                event.getInventory().setItem(offer.slot(), icon(offer, price, now));
                holder.shownPrices.put(offer.slot(), price.finalPrice());
                holder.promotionKeys.put(offer.slot(), promotionKey(price));
                messages.send(player, "shop-price-changed");
                return;
            }
            purchase(player, shop, offer, price, holder);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ShopHolder) event.setCancelled(true);
    }

    private void purchase(Player player, ShopDefinition shop, ShopOffer offer, ActivePrice activePrice, ShopHolder holder) {
        if (!offer.permission().isBlank() && !player.hasPermission(offer.permission())) {
            messages.send(player, "no-permission");
            return;
        }
        if (!offer.available()) {
            messages.send(player, "out-of-stock");
            return;
        }
        ItemStack reward = offer.product();
        if (!canFit(player.getInventory().getStorageContents(), reward)) {
            messages.send(player, "inventory-full");
            return;
        }
        double price = activePrice.finalPrice();
        if (price <= 0) {
            applyFreePurchase(player, shop, offer, activePrice, holder);
            return;
        }
        if (!economy.available() && !economy.hook()) {
            messages.send(player, "aurum-economy-unavailable");
            return;
        }
        if (!pending.add(player.getUniqueId())) return;
        String operation = UUID.randomUUID().toString();
        Map<String, String> metadata = Map.of("plugin", "AddonsNPC", "operation", operation,
                "shop", shop.id(), "offer-slot", Integer.toString(offer.slot()));
        economy.reserve("npc-shop:" + operation, AccountId.player(player.getUniqueId()),
                new AccountId(AccountType.NPC_SHOP, shop.id()), price, TransactionCategory.NPC_PURCHASE,
                "npc-shop", shop.id() + ":" + offer.slot(), metadata)
                .whenComplete((result, error) -> runMain(() -> reservedPurchase(player, shop.id(), offer.slot(),
                        price, holder, result, error)));
    }

    private void reservedPurchase(Player player, String shopId, int slot, double reservedPrice, ShopHolder holder,
                                  HoldResult result, Throwable error) {
        if (error != null || result == null || (result.status() != HoldResult.Status.SUCCESS
                && result.status() != HoldResult.Status.DUPLICATE) || result.hold().isEmpty()) {
            pending.remove(player.getUniqueId());
            messages.send(player, result != null && result.status() == HoldResult.Status.INSUFFICIENT_FUNDS
                    ? "insufficient-funds" : "purchase-failed");
            return;
        }
        NpcSaga saga;
        try { saga = sagas.begin(NpcSaga.Kind.SHOP_PURCHASE, player.getUniqueId(), result.hold().orElseThrow()); }
        catch (RuntimeException failure) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not journal NPC shop purchase", failure);
            economy.release(NpcSaga.held(NpcSaga.Kind.SHOP_PURCHASE, player.getUniqueId(), result.hold().orElseThrow()));
            pending.remove(player.getUniqueId());
            messages.send(player, "purchase-failed");
            return;
        }
        ShopDefinition shop = repository.get(shopId);
        ShopOffer offer = shop == null ? null : shop.offers().get(slot);
        long now = System.currentTimeMillis();
        ActivePrice price = offer == null ? null : activePrice(shop, offer, now);
        if (offer == null || !offer.available() || Math.abs(price.finalPrice() - reservedPrice) > 0.0000001
                || !canFit(player.getInventory().getStorageContents(), offer.product())) {
            releaseFailed(saga, player, "shop-price-changed");
            return;
        }
        ItemStack reward = offer.product();
        int stockBefore = offer.stock();
        Map<String, Object> values = placeholders(player, offer, price, economy.balance(player), now);
        try {
            HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(reward);
            if (!leftovers.isEmpty()) {
                throw new IllegalStateException("Inventory changed during purchase");
            }
            offer.consume();
            repository.save();
            NpcSaga applied = sagas.markApplied(saga);
            economy.capture(applied).whenComplete((capture, failure) -> runMain(() -> {
                pending.remove(player.getUniqueId());
                if (failure == null && capture != null && (capture.status() == HoldResult.Status.SUCCESS
                        || capture.status() == HoldResult.Status.DUPLICATE)) {
                    for (String command : offer.commands()) try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                stripSlash(MessageService.replace(command, values)));
                    } catch (RuntimeException commandError) {
                        plugin.getLogger().warning("Shop post-purchase command failed: " + commandError.getMessage());
                    }
                    sagas.complete(applied);
                    refreshOffer(player, holder, shop, offer);
                    player.updateInventory();
                    messages.send(player, "purchase-success", values);
                } else if (capture != null && capture.status() != HoldResult.Status.UNAVAILABLE) {
                    offer.stock(stockBefore); repository.save(); player.getInventory().removeItem(reward);
                    economy.release(applied); sagas.complete(applied); player.updateInventory();
                    messages.send(player, "purchase-failed");
                } else {
                    plugin.getLogger().warning("NPC shop purchase " + applied.id()
                            + " is awaiting AurumCore recovery");
                    messages.send(player, "purchase-failed");
                }
            }));
        } catch (RuntimeException exception) {
            offer.stock(stockBefore);
            player.getInventory().removeItem(reward);
            economy.release(saga); sagas.complete(saga); pending.remove(player.getUniqueId());
            plugin.getLogger().warning("Rolled back purchase for " + player.getName() + ": " + exception.getMessage());
            messages.send(player, "purchase-failed");
        }
    }

    private void applyFreePurchase(Player player, ShopDefinition shop, ShopOffer offer,
                                   ActivePrice price, ShopHolder holder) {
        ItemStack reward = offer.product();
        int stockBefore = offer.stock();
        Map<String, Object> values = placeholders(player, offer, price, economy.balance(player), System.currentTimeMillis());
        try {
            if (!player.getInventory().addItem(reward).isEmpty()) throw new IllegalStateException("Inventory changed");
            offer.consume(); repository.save();
            for (String command : offer.commands()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    stripSlash(MessageService.replace(command, values)));
            refreshOffer(player, holder, shop, offer); player.updateInventory();
            messages.send(player, "purchase-success", values);
        } catch (RuntimeException error) {
            offer.stock(stockBefore); player.getInventory().removeItem(reward); repository.save();
            messages.send(player, "purchase-failed");
        }
    }

    private void releaseFailed(NpcSaga saga, Player player, String message) {
        economy.release(saga).whenComplete((ignored, error) -> sagas.complete(saga));
        pending.remove(player.getUniqueId()); messages.send(player, message);
    }

    private void refreshOffer(Player player, ShopHolder holder, ShopDefinition shop, ShopOffer offer) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof ShopHolder)) return;
        long now = System.currentTimeMillis(); ActivePrice refreshed = activePrice(shop, offer, now);
        player.getOpenInventory().getTopInventory().setItem(offer.slot(), icon(offer, refreshed, now));
        holder.shownPrices.put(offer.slot(), refreshed.finalPrice());
        holder.promotionKeys.put(offer.slot(), promotionKey(refreshed));
    }

    private void runMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run(); else Bukkit.getScheduler().runTask(plugin, action);
    }

    private Map<String, Object> placeholders(Player player, ShopOffer offer, ActivePrice price, double balance, long now) {
        Map<String, Object> values = new HashMap<>(pricePlaceholders(offer, price, now));
        values.put("player", player.getName());
        values.put("balance", economy.format(balance));
        values.put("item", offer.item().name().toLowerCase());
        return values;
    }

    private Map<String, Object> pricePlaceholders(ShopOffer offer, ActivePrice price, long now) {
        return Map.of(
                "price", economy.format(price.finalPrice()),
                "base_price", economy.format(price.basePrice()),
                "discount_percent", percentage(price.modifier().percent()),
                "discount_remaining", messages.remaining(price.modifier(), now),
                "amount", offer.quantity(),
                "stock", stockText(offer));
    }

    private static ActivePrice activePrice(ShopDefinition shop, ShopOffer offer, long now) {
        TimedPercentage modifier = offer.discount().active(now) ? offer.discount() : shop.discount();
        boolean discounted = modifier.active(now);
        return new ActivePrice(offer.price(), discounted ? modifier.discount(offer.price(), now) : offer.price(), modifier, discounted);
    }

    private static String percentage(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String promotionKey(ActivePrice price) {
        return price.discounted() ? price.modifier().percent() + ":" + price.modifier().expiresAtMillis() : "none";
    }

    private static String stockText(ShopOffer offer) {
        return offer.unlimited() ? "∞" : Integer.toString(offer.stock());
    }

    static boolean canFit(ItemStack[] contents, ItemStack reward) {
        int remaining = reward.getAmount();
        int max = reward.getMaxStackSize();
        for (ItemStack current : contents) {
            if (current == null || current.getType() == Material.AIR) {
                remaining -= max;
            } else if (current.isSimilar(reward)) {
                remaining -= Math.max(0, Math.min(max, current.getMaxStackSize()) - current.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private static final class ShopHolder implements InventoryHolder {
        private final String shopId;
        private final Map<Integer, Double> shownPrices = new HashMap<>();
        private final Map<Integer, String> promotionKeys = new HashMap<>();
        private Inventory inventory;

        private ShopHolder(String shopId) {
            this.shopId = shopId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record ActivePrice(double basePrice, double finalPrice, TimedPercentage modifier, boolean discounted) {}
}
