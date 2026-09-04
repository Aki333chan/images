package dev.addons.npc.service;

import dev.addons.npc.config.ShopRepository;
import dev.addons.npc.model.ShopDefinition;
import dev.addons.npc.model.ShopOffer;
import dev.addons.npc.model.TimedPercentage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

public final class ShopService implements Listener {
    private final JavaPlugin plugin;
    private final ShopRepository repository;
    private final EconomyService economy;
    private final MessageService messages;

    public ShopService(JavaPlugin plugin, ShopRepository repository, EconomyService economy, MessageService messages) {
        this.plugin = plugin;
        this.repository = repository;
        this.economy = economy;
        this.messages = messages;
    }

    public void open(Player player, String shopId) {
        if (!player.hasPermission("addonsnpc.shop")) {
            messages.send(player, "no-permission");
            return;
        }
        ShopDefinition shop = repository.get(shopId);
        if (shop == null) {
            messages.raw(player, messages.prefix() + "&cShop '&f" + shopId + "&c' was not found.", Map.of());
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
                ? "&a&lСКИДКА &r" + offer.displayName() : offer.displayName()));
        if (activePrice.discounted()) meta.setEnchantmentGlintOverride(true);
        List<String> lore = new ArrayList<>();
        Map<String, Object> values = pricePlaceholders(offer, activePrice, now);
        if (offer.lore().isEmpty()) {
            lore.add("&7Receive: &f" + offer.item().name() + " x" + offer.quantity());
            if (!activePrice.discounted()) lore.add("&6Price: &e{price}");
            lore.add("&7Stock: &f{stock}");
        } else {
            lore.addAll(offer.lore());
        }
        if (activePrice.discounted()) {
            lore.add("");
            lore.add("&a&lСКИДКА -{discount_percent}%");
            lore.add("&8&mОбычная цена: {base_price}");
            lore.add("&6Цена по акции: &e&l{price}");
            lore.add("&7Осталось: &f{discount_remaining}");
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
        if (price > 0 && !economy.available()) {
            messages.send(player, "vault-unavailable");
            return;
        }
        double balance = economy.balance(player);
        Map<String, Object> values = placeholders(player, offer, activePrice, balance, System.currentTimeMillis());
        if (price > balance) {
            messages.send(player, "insufficient-funds", values);
            return;
        }
        Optional<String> failure = price <= 0 ? Optional.empty() : economy.withdraw(player, price);
        if (failure.isPresent()) {
            plugin.getLogger().warning("Vault transaction failed for " + player.getName() + ": " + failure.get());
            messages.send(player, "purchase-failed");
            return;
        }
        int stockBefore = offer.stock();
        try {
            HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(reward);
            if (!leftovers.isEmpty()) {
                throw new IllegalStateException("Inventory changed during purchase");
            }
            for (String command : offer.commands()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(MessageService.replace(command, values)));
            }
            offer.consume();
            repository.save();
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ShopHolder) {
                long now = System.currentTimeMillis();
                ActivePrice refreshed = activePrice(shop, offer, now);
                player.getOpenInventory().getTopInventory().setItem(offer.slot(), icon(offer, refreshed, now));
                holder.shownPrices.put(offer.slot(), refreshed.finalPrice());
                holder.promotionKeys.put(offer.slot(), promotionKey(refreshed));
            }
            player.updateInventory();
            messages.send(player, "purchase-success", values);
        } catch (RuntimeException exception) {
            offer.stock(stockBefore);
            player.getInventory().removeItem(reward);
            economy.refund(player, price);
            plugin.getLogger().warning("Rolled back purchase for " + player.getName() + ": " + exception.getMessage());
            messages.send(player, "purchase-failed");
        }
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
                "discount_remaining", price.modifier().remaining(now),
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
