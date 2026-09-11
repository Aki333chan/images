package dev.addons.npc.service;

import dev.addons.npc.model.ExchangeOffer;
import dev.addons.npc.model.ExchangerDefinition;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.EconomyMode;
import ovh.aurumgg.core.api.ExchangeQuote;
import ovh.aurumgg.core.api.ExchangeRequest;
import ovh.aurumgg.core.api.ExchangeResult;

/** AurumCore-backed exchange GUI. No Vault fallback is possible because Vault is single-currency. */
public final class AurumExchangeService implements Listener {
    private final JavaPlugin plugin;
    private final dev.addons.npc.config.ExchangerRepository repository;
    private final MessageService messages;
    private final Set<UUID> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile AurumEconomyApi economy;

    public AurumExchangeService(JavaPlugin plugin, dev.addons.npc.config.ExchangerRepository repository,
                                MessageService messages) {
        this.plugin = plugin;
        this.repository = repository;
        this.messages = messages;
    }

    public boolean hook() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("AurumCore")) {
            economy = null;
            return false;
        }
        try {
            RegisteredServiceProvider<AurumEconomyApi> registration = plugin.getServer()
                    .getServicesManager().getRegistration(AurumEconomyApi.class);
            AurumEconomyApi selected = registration == null ? null : registration.getProvider();
            economy = selected != null && selected.mode() == EconomyMode.ACTIVE ? selected : null;
        } catch (LinkageError error) {
            economy = null;
            plugin.getLogger().warning("AurumCore API could not be linked: " + error.getClass().getSimpleName());
        }
        return economy != null;
    }

    public boolean available() { return economy != null; }
    public List<String> currencyIds() {
        AurumEconomyApi current = economy;
        return current == null ? List.of() : current.currencies().stream().map(value -> value.id()).toList();
    }

    public void open(Player player, String exchangerId) {
        if (!player.hasPermission("addonsnpc.exchanger")) {
            messages.send(player, "no-permission");
            return;
        }
        ExchangerDefinition exchanger = repository.get(exchangerId);
        if (exchanger == null) {
            messages.send(player, "exchanger-not-found", Map.of("exchanger", exchangerId));
            return;
        }
        if (!available() && !hook()) {
            messages.send(player, "aurum-exchange-unavailable");
            return;
        }
        ExchangeHolder holder = new ExchangeHolder(exchanger.id());
        Inventory inventory = Bukkit.createInventory(holder, exchanger.size(),
                MessageService.colorize(exchanger.title()));
        holder.inventory = inventory;
        exchanger.offers().values().forEach(offer -> {
            inventory.setItem(offer.slot(), loadingIcon(offer));
            quote(player, exchanger, offer).whenComplete((quote, error) -> onQuote(player, holder, offer, quote, error));
        });
        player.openInventory(inventory);
    }

    private CompletionStage<Optional<ExchangeQuote>> quote(Player player, ExchangerDefinition exchanger,
                                                             ExchangeOffer offer) {
        AurumEconomyApi current = economy;
        if (current == null) return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
        return current.quoteExchange(AccountId.player(player.getUniqueId()), offer.fromCurrency(),
                offer.toCurrency(), offer.sourceAmount(), Map.of(
                        "plugin", "AddonsNPC", "npc", exchanger.id(),
                        "exchanger", exchanger.id(), "offer-slot", Integer.toString(offer.slot())));
    }

    private void onQuote(Player player, ExchangeHolder holder, ExchangeOffer offer,
                         Optional<ExchangeQuote> result, Throwable error) {
        runMain(() -> {
            if (!sameInventory(player, holder)) return;
            if (error != null || result == null || result.isEmpty()) {
                holder.quotes.remove(offer.slot());
                holder.inventory.setItem(offer.slot(), unavailableIcon(offer));
                return;
            }
            ExchangeQuote quote = result.get();
            holder.quotes.put(offer.slot(), quote);
            holder.inventory.setItem(offer.slot(), quoteIcon(offer, quote, false));
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder rawHolder = event.getInventory().getHolder();
        if (!(rawHolder instanceof ExchangeHolder) && !(rawHolder instanceof ConfirmationHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getInventory().getSize()) return;
        if (rawHolder instanceof ExchangeHolder holder) select(player, holder, event.getRawSlot());
        else confirm(player, (ConfirmationHolder) rawHolder, event.getRawSlot());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ExchangeHolder
                || event.getInventory().getHolder() instanceof ConfirmationHolder) event.setCancelled(true);
    }

    private void select(Player player, ExchangeHolder holder, int slot) {
        ExchangerDefinition exchanger = repository.get(holder.exchangerId);
        ExchangeOffer offer = exchanger == null ? null : exchanger.offers().get(slot);
        if (offer == null) return;
        if (!offer.permission().isBlank() && !player.hasPermission(offer.permission())) {
            messages.send(player, "no-permission");
            return;
        }
        ExchangeQuote shown = holder.quotes.get(slot);
        if (shown == null) {
            messages.send(player, "exchange-quote-unavailable");
            return;
        }
        quote(player, exchanger, offer).whenComplete((fresh, error) -> runMain(() -> {
            if (error != null || fresh == null || fresh.isEmpty()) {
                messages.send(player, "exchange-quote-unavailable");
                open(player, exchanger.id());
                return;
            }
            if (!signature(shown).equals(signature(fresh.get()))) {
                messages.send(player, "exchange-quote-changed");
            }
            openConfirmation(player, exchanger, offer, fresh.get());
        }));
    }

    private void openConfirmation(Player player, ExchangerDefinition exchanger,
                                  ExchangeOffer offer, ExchangeQuote quote) {
        ConfirmationHolder holder = new ConfirmationHolder(exchanger.id(), offer.slot(), quote);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                messages.formatKey("gui.exchanger.confirm-title", Map.of()));
        holder.inventory = inventory;
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        inventory.setItem(11, named(Material.LIME_CONCRETE,
                messages.text("gui.exchanger.confirm"), List.of(messages.text("gui.exchanger.confirm-hint"))));
        inventory.setItem(13, quoteIcon(offer, quote, true));
        inventory.setItem(15, named(Material.RED_CONCRETE,
                messages.text("gui.exchanger.cancel"), List.of(messages.text("gui.exchanger.cancel-hint"))));
        player.openInventory(inventory);
    }

    private void confirm(Player player, ConfirmationHolder holder, int slot) {
        if (slot == 15) {
            open(player, holder.exchangerId);
            return;
        }
        if (slot != 11) return;
        ExchangerDefinition exchanger = repository.get(holder.exchangerId);
        ExchangeOffer offer = exchanger == null ? null : exchanger.offers().get(holder.offerSlot);
        if (offer == null || !offer.fromCurrency().equals(holder.quote.fromCurrency().id())
                || !offer.toCurrency().equals(holder.quote.toCurrency().id())
                || offer.sourceAmount().compareTo(holder.quote.sourceAmount()) != 0) {
            messages.send(player, "exchange-quote-changed");
            open(player, holder.exchangerId);
            return;
        }
        if (!offer.permission().isBlank() && !player.hasPermission(offer.permission())) {
            messages.send(player, "no-permission");
            open(player, holder.exchangerId);
            return;
        }
        if (!pending.add(player.getUniqueId())) return;
        AurumEconomyApi current = economy;
        if (current == null) {
            pending.remove(player.getUniqueId());
            messages.send(player, "aurum-exchange-unavailable");
            return;
        }
        ExchangeQuote quote = holder.quote;
        holder.inventory.setItem(11, named(Material.YELLOW_CONCRETE,
                messages.text("gui.exchanger.processing"), List.of()));
        ExchangeRequest request = new ExchangeRequest(holder.idempotencyKey,
                AccountId.player(player.getUniqueId()), quote.fromCurrency().id(), quote.toCurrency().id(),
                quote.sourceAmount(), quote.ruleId(), quote.ruleRevision(), quote.targetAmount(), quote.expiresAt(),
                Map.of("plugin", "AddonsNPC", "npc", holder.exchangerId,
                        "exchanger", holder.exchangerId, "offer-slot", Integer.toString(holder.offerSlot)));
        current.exchange(request).whenComplete((result, error) -> runMain(() -> {
            pending.remove(player.getUniqueId());
            if (error != null || result == null || result.status() == ExchangeResult.Status.UNAVAILABLE) {
                messages.send(player, "exchange-failed");
                if (sameInventory(player, holder)) {
                    holder.inventory.setItem(11, named(Material.LIME_CONCRETE,
                            messages.text("gui.exchanger.retry"), List.of(messages.text("gui.exchanger.confirm-hint"))));
                }
                return;
            } else if (result.status() == ExchangeResult.Status.REJECTED) {
                messages.send(player, "exchange-rejected", Map.of("reason", result.message()));
            } else {
                ExchangeQuote committed = result.quote().orElse(quote);
                messages.send(player, "exchange-success", values(committed));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.15f);
            }
            open(player, holder.exchangerId);
        }));
    }

    private ItemStack loadingIcon(ExchangeOffer offer) {
        return named(offer.icon(), offer.displayName(), List.of(messages.text("gui.exchanger.loading")));
    }

    private ItemStack unavailableIcon(ExchangeOffer offer) {
        return named(Material.BARRIER, offer.displayName(), List.of(messages.text("gui.exchanger.unavailable")));
    }

    private ItemStack quoteIcon(ExchangeOffer offer, ExchangeQuote quote, boolean confirmation) {
        Map<String, Object> values = values(quote);
        List<String> lore = new ArrayList<>();
        if (offer.lore().isEmpty()) {
            lore.add(messages.text("gui.exchanger.give"));
            lore.add(messages.text("gui.exchanger.receive"));
            lore.add(messages.text("gui.exchanger.rate"));
            lore.add(messages.text("gui.exchanger.fee"));
        } else lore.addAll(offer.lore());
        lore.add("");
        lore.add(messages.text(confirmation ? "gui.exchanger.review" : "gui.exchanger.click"));
        ItemStack item = named(offer.icon(), offer.displayName(),
                lore.stream().map(line -> messages.format(line, values)).toList());
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(MessageService.colorize(name));
        meta.setLore(lore.stream().map(MessageService::colorize).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static Map<String, Object> values(ExchangeQuote quote) {
        BigDecimal effectiveRate = quote.convertedAmount().signum() == 0 ? BigDecimal.ZERO
                : quote.targetAmount().divide(quote.convertedAmount(), 8, RoundingMode.DOWN).stripTrailingZeros();
        Map<String, Object> values = new HashMap<>();
        values.put("from", quote.fromCurrency().displayName());
        values.put("to", quote.toCurrency().displayName());
        values.put("source", amount(quote.sourceAmount(), quote.fromCurrency().symbol()));
        values.put("fee", amount(quote.feeAmount(), quote.fromCurrency().symbol()));
        values.put("receive", amount(quote.targetAmount(), quote.toCurrency().symbol()));
        values.put("rate", effectiveRate.toPlainString());
        values.put("seconds", Math.max(0, java.time.Duration.between(Instant.now(), quote.expiresAt()).toSeconds()));
        return values;
    }

    private static String signature(ExchangeQuote quote) {
        return quote.ruleId() + ':' + quote.ruleRevision() + ':' + quote.sourceAmount() + ':' + quote.targetAmount();
    }
    private static String amount(BigDecimal value, String symbol) {
        return value.stripTrailingZeros().toPlainString() + symbol;
    }
    private boolean sameInventory(Player player, InventoryHolder holder) {
        return player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder;
    }
    private void runMain(Runnable task) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, task);
    }

    private static final class ExchangeHolder implements InventoryHolder {
        private final String exchangerId;
        private final Map<Integer, ExchangeQuote> quotes = new HashMap<>();
        private Inventory inventory;
        private ExchangeHolder(String exchangerId) { this.exchangerId = exchangerId; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class ConfirmationHolder implements InventoryHolder {
        private final String exchangerId;
        private final int offerSlot;
        private final ExchangeQuote quote;
        private final String idempotencyKey = "npc-exchange:" + UUID.randomUUID();
        private Inventory inventory;
        private ConfirmationHolder(String exchangerId, int offerSlot, ExchangeQuote quote) {
            this.exchangerId = exchangerId;
            this.offerSlot = offerSlot;
            this.quote = quote;
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}
