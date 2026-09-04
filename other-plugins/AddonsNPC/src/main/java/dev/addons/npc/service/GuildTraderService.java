package dev.addons.npc.service;

import dev.addons.npc.config.GuildTraderRepository;
import dev.addons.npc.model.GuildBonusOffer;
import dev.addons.npc.model.GuildBonusType;
import dev.addons.npc.model.GuildTraderDefinition;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.bukkit.plugin.java.JavaPlugin;

/** GUI merchant that exchanges Vault currency for AurumGuilds bonuses. */
public final class GuildTraderService implements Listener {
    private final JavaPlugin plugin;
    private final GuildTraderRepository repository;
    private final EconomyService economy;
    private final MessageService messages;
    private final AurumGuildsHook guilds;
    private final Set<UUID> pendingPurchases = new HashSet<>();

    public GuildTraderService(JavaPlugin plugin, GuildTraderRepository repository, EconomyService economy,
                              MessageService messages, AurumGuildsHook guilds) {
        this.plugin = plugin;
        this.repository = repository;
        this.economy = economy;
        this.messages = messages;
        this.guilds = guilds;
    }

    public void open(Player player, String traderId) {
        if (!player.hasPermission("addonsnpc.guildtrader")) {
            messages.send(player, "no-permission");
            return;
        }
        GuildTraderDefinition trader = repository.get(traderId);
        if (trader == null) {
            messages.send(player, "guild-trader-not-found", Map.of("trader", traderId));
            return;
        }
        if (!guilds.available()) {
            messages.send(player, "guilds-unavailable");
            return;
        }
        Optional<AurumGuildsHook.Membership> membership = guilds.membership(player.getUniqueId());
        if (membership.isEmpty()) {
            messages.send(player, "guild-required");
            return;
        }
        GuildTraderHolder holder = new GuildTraderHolder(trader.id(), membership.get().guildId());
        Inventory inventory = Bukkit.createInventory(holder, trader.size(), MessageService.colorize(trader.title()));
        holder.inventory = inventory;
        refresh(inventory, holder, trader, membership.get());
        player.openInventory(inventory);
    }

    private void refresh(Inventory inventory, GuildTraderHolder holder, GuildTraderDefinition trader,
                         AurumGuildsHook.Membership membership) {
        List<AurumGuildsHook.ActiveBonus> active = guilds.bonuses(membership.guildId());
        holder.signatures.clear();
        for (GuildBonusOffer offer : trader.offers().values()) {
            AurumGuildsHook.ActiveBonus current = active.stream()
                    .filter(bonus -> bonus.type() == offer.type()).findFirst().orElse(null);
            inventory.setItem(offer.slot(), icon(offer, trader, membership, current));
            holder.signatures.put(offer.slot(), signature(trader, offer, current));
        }
    }

    private ItemStack icon(GuildBonusOffer offer, GuildTraderDefinition trader,
                           AurumGuildsHook.Membership membership, AurumGuildsHook.ActiveBonus current) {
        ItemStack icon = new ItemStack(offer.icon());
        ItemMeta meta = icon.getItemMeta();
        meta.setDisplayName(MessageService.colorize(offer.displayName()));
        if (current != null) meta.setEnchantmentGlintOverride(true);
        List<String> lore = new ArrayList<>();
        Map<String, Object> placeholders = placeholders(offer, membership, current);
        if (!offer.lore().isEmpty()) lore.addAll(offer.lore());
        else {
            lore.add("&7Усиление: &f{bonus_value}");
            lore.add("&7Срок: &f{duration}");
            lore.add("&6Цена: &e{price}");
        }
        lore.add("");
        if (current == null) lore.add("&8Сейчас бонус этого вида не действует.");
        else {
            lore.add("&aДействует: &f{current_value}");
            lore.add("&aОсталось: &f{current_remaining}");
            lore.add("&cПокупка заменит текущий бонус.");
        }
        if (membership.rankWeight() < trader.requiredRank().weight()) {
            lore.add("");
            lore.add("&cТребуемый ранг: &f" + rankTitle(trader.requiredRank().name()));
        } else {
            lore.add("");
            lore.add("&eНажмите, чтобы купить для &f{guild}&e.");
        }
        meta.setLore(lore.stream().map(line -> messages.format(line, placeholders)).toList());
        icon.setItemMeta(meta);
        return icon;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuildTraderHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getInventory().getSize()) return;
        GuildTraderDefinition trader = repository.get(holder.traderId);
        GuildBonusOffer offer = trader == null ? null : trader.offers().get(event.getRawSlot());
        if (offer == null) return;

        Optional<AurumGuildsHook.Membership> membership = guilds.membership(player.getUniqueId());
        if (membership.isEmpty() || membership.get().guildId() != holder.guildId) {
            player.closeInventory();
            messages.send(player, "guild-membership-changed");
            return;
        }
        AurumGuildsHook.ActiveBonus current = guilds.bonuses(holder.guildId).stream()
                .filter(bonus -> bonus.type() == offer.type()).findFirst().orElse(null);
        String currentSignature = signature(trader, offer, current);
        if (!currentSignature.equals(holder.signatures.get(offer.slot()))) {
            refresh(event.getInventory(), holder, trader, membership.get());
            messages.send(player, "guild-bonus-changed");
            return;
        }
        purchase(player, trader, offer, membership.get());
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GuildTraderHolder) event.setCancelled(true);
    }

    private void purchase(Player player, GuildTraderDefinition trader, GuildBonusOffer offer,
                          AurumGuildsHook.Membership membership) {
        if (membership.rankWeight() < trader.requiredRank().weight()) {
            messages.send(player, "guild-rank-required", Map.of("rank", rankTitle(trader.requiredRank().name())));
            return;
        }
        if (!offer.permission().isBlank() && !player.hasPermission(offer.permission())) {
            messages.send(player, "no-permission");
            return;
        }
        if (!pendingPurchases.add(player.getUniqueId())) {
            messages.send(player, "guild-purchase-pending");
            return;
        }
        double price = offer.price();
        if (price > 0 && !economy.available()) economy.hook();
        if (price > 0 && !economy.available()) {
            pendingPurchases.remove(player.getUniqueId());
            messages.send(player, "vault-unavailable");
            return;
        }
        double balance = economy.balance(player);
        Map<String, Object> placeholders = placeholders(offer, membership, null);
        placeholders = new HashMap<>(placeholders);
        placeholders.put("balance", economy.format(balance));
        if (price > balance) {
            pendingPurchases.remove(player.getUniqueId());
            messages.send(player, "insufficient-funds", placeholders);
            return;
        }
        Optional<String> withdrawal = price <= 0 ? Optional.empty() : economy.withdraw(player, price);
        if (withdrawal.isPresent()) {
            pendingPurchases.remove(player.getUniqueId());
            plugin.getLogger().warning("Guild bonus payment failed for " + player.getName() + ": " + withdrawal.get());
            messages.send(player, "purchase-failed");
            return;
        }

        Duration duration = offer.permanent() ? null : Duration.ofSeconds(offer.durationSeconds());
        String actor = "AurumNPC:" + trader.id() + "/" + player.getName();
        guilds.grant(membership.guildId(), offer.type(), offer.magnitude(), duration, actor)
                .whenComplete((result, error) -> completePurchase(player.getUniqueId(), trader.id(), price, result, error));
    }

    private void completePurchase(UUID playerId, String traderId, double price,
                                  AurumGuildsHook.GrantResult result, Throwable error) {
        if (!plugin.isEnabled()) {
            plugin.getLogger().severe("Guild bonus transaction completed while AddonsNPC was disabled for " + playerId
                    + "; verify the payment and bonus manually.");
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingPurchases.remove(playerId);
            Player player = Bukkit.getPlayer(playerId);
            boolean success = error == null && result != null && result.ok();
            if (!success) {
                Optional<String> refundFailure = price <= 0 ? Optional.empty()
                        : economy.deposit(Bukkit.getOfflinePlayer(playerId), price);
                if (refundFailure.isPresent()) {
                    plugin.getLogger().severe("Could not refund guild bonus purchase for " + playerId + ": " + refundFailure.get());
                }
                if (player != null) messages.send(player, refundFailure.isPresent()
                        ? "guild-purchase-refund-failed" : "guild-purchase-failed",
                        Map.of("reason", result == null ? "неизвестная ошибка" : result.message()));
                return;
            }
            if (player != null) {
                messages.send(player, "guild-purchase-success", Map.of("result", result.message()));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof GuildTraderHolder holder
                        && holder.traderId.equals(traderId)) open(player, traderId);
            }
        });
    }

    private Map<String, Object> placeholders(GuildBonusOffer offer, AurumGuildsHook.Membership membership,
                                              AurumGuildsHook.ActiveBonus current) {
        return Map.of(
                "guild", membership.guildName(),
                "guild_tag", membership.guildTag(),
                "bonus", offer.type().title(),
                "bonus_type", offer.type().name().toLowerCase(),
                "bonus_value", offer.type().describe(offer.magnitude()),
                "magnitude", number(offer.magnitude()),
                "duration", offer.permanent() ? "навсегда" : humanDuration(Duration.ofSeconds(offer.durationSeconds())),
                "price", economy.format(offer.price()),
                "current_value", current == null ? "нет" : current.type().describe(current.magnitude()),
                "current_remaining", current == null ? "нет" : current.permanent()
                        ? "навсегда" : humanDuration(current.remaining(Instant.now())));
    }

    private static String signature(GuildTraderDefinition trader, GuildBonusOffer offer, AurumGuildsHook.ActiveBonus current) {
        return offer.type() + ":" + offer.magnitude() + ":" + offer.durationSeconds() + ":" + offer.price()
                + ":" + offer.icon() + ":" + offer.displayName() + ":" + offer.permission() + ":" + offer.lore().hashCode()
                + ":" + trader.requiredRank()
                + "|" + (current == null ? "none" : current.type() + ":" + current.magnitude() + ":" + current.expiresAt());
    }

    public static String humanDuration(Duration duration) {
        if (duration == null) return "навсегда";
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds >= 1209600 && seconds % 604800 == 0) return seconds / 604800 + "н";
        if (seconds >= 86400 && seconds % 86400 == 0) return seconds / 86400 + "д";
        if (seconds >= 3600 && seconds % 3600 == 0) return seconds / 3600 + "ч";
        if (seconds >= 60 && seconds % 60 == 0) return seconds / 60 + "м";
        return seconds + "с";
    }

    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static String rankTitle(String rank) {
        return switch (rank) { case "LEADER" -> "лидер"; case "OFFICER" -> "офицер"; default -> "участник"; };
    }

    private static final class GuildTraderHolder implements InventoryHolder {
        private final String traderId;
        private final long guildId;
        private final Map<Integer, String> signatures = new HashMap<>();
        private Inventory inventory;

        private GuildTraderHolder(String traderId, long guildId) {
            this.traderId = traderId;
            this.guildId = guildId;
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
