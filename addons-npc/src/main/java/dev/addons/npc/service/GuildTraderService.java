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
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.ClaimRequest;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.HoldSnapshot;
import ovh.aurumgg.core.api.TransactionCategory;

/** GUI merchant that exchanges AurumCore currency for AurumGuilds bonuses. */
public final class GuildTraderService implements Listener {
    private final JavaPlugin plugin;
    private final GuildTraderRepository repository;
    private final EconomyService economy;
    private final MessageService messages;
    private final AurumGuildsHook guilds;
    private final ClaimDelivery delivery;
    private final Set<UUID> pendingPurchases = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public GuildTraderService(JavaPlugin plugin, GuildTraderRepository repository, EconomyService economy,
                              MessageService messages, AurumGuildsHook guilds, ClaimDelivery delivery) {
        this.plugin = plugin;
        this.repository = repository;
        this.economy = economy;
        this.messages = messages;
        this.guilds = guilds;
        this.delivery = delivery;
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
        Map<String, Object> placeholders = placeholders(offer, membership, current);
        meta.setDisplayName(messages.format(offer.displayName(), placeholders));
        if (current != null) meta.setEnchantmentGlintOverride(true);
        List<String> lore = new ArrayList<>();
        if (!offer.lore().isEmpty()) lore.addAll(offer.lore());
        else {
            lore.add(messages.text("gui.guild-trader.bonus"));
            lore.add(messages.text("gui.guild-trader.duration"));
            lore.add(messages.text("gui.guild-trader.price"));
        }
        lore.add("");
        if (current == null) lore.add(messages.text("gui.guild-trader.inactive"));
        else {
            lore.add(messages.text("gui.guild-trader.active"));
            lore.add(messages.text("gui.guild-trader.remaining"));
            lore.add(messages.text("gui.guild-trader.replaces"));
        }
        if (membership.rankWeight() < trader.requiredRank().weight()) {
            lore.add("");
            lore.add(messages.text("gui.guild-trader.required-rank")
                    .replace("{rank}", messages.rank(trader.requiredRank().name())));
        } else {
            lore.add("");
            lore.add(messages.text("gui.guild-trader.click-to-buy"));
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
            messages.send(player, "guild-rank-required", Map.of("rank", messages.rank(trader.requiredRank().name())));
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
            messages.send(player, "aurum-economy-unavailable");
            return;
        }
        Duration duration = offer.permanent() ? null : Duration.ofSeconds(offer.durationSeconds());
        String operation = UUID.randomUUID().toString();
        String actor = "AurumNPC:" + trader.id() + "/" + player.getName() + "#" + operation;
        if (price <= 0) {
            guilds.grant(membership.guildId(), offer.type(), offer.magnitude(), duration, actor)
                    .whenComplete((result, error) -> completeFreePurchase(player.getUniqueId(), trader.id(), result, error));
            return;
        }
        Map<String, String> metadata = Map.of("plugin", "AddonsNPC", "operation", operation,
                "guild-trader", trader.id(), "offer-slot", Integer.toString(offer.slot()),
                "guild-id", Long.toString(membership.guildId()), "guild-actor", actor);
        economy.reserve("npc-guild:" + operation, AccountId.player(player.getUniqueId()),
                new AccountId(AccountType.NPC_SHOP, "guild-trader:" + trader.id()), price,
                TransactionCategory.NPC_PURCHASE, "npc-guild-bonus",
                trader.id() + ":" + offer.slot(), metadata).whenComplete((hold, error) -> runMain(() ->
                reservedGuildPurchase(player.getUniqueId(), trader.id(), offer.slot(), membership.guildId(),
                        duration, actor, hold, error)));
    }

    /**
     * Резерв прошёл — записать долг и только потом что-то менять.
     *
     * <h2>Что здесь было не так</h2>
     *
     * Прежний порядок выдавал бонус и лишь затем отмечал журнал. Авария между
     * этими шагами оставляла резерв, который перезапущенный плагин считал
     * неприменённым, — и распознавал уже выданный бонус по уникальной строке
     * {@code guild-actor}, которой тот был подписан. Это работало, но ровно
     * пока бонус существует: истёкший до восстановления бонус выглядел
     * невыданным, резерв освобождался, и гильдия оставалась с усилением,
     * которое никто не оплатил.
     *
     * Курсор заявки не зависит от того, жив ли ещё бонус. Он говорит, какие
     * шаги выполнены, и этот ответ не истекает.
     */
    private void reservedGuildPurchase(UUID playerId, String traderId, int slot, long guildId,
                                       Duration duration, String actor, HoldResult hold, Throwable error) {
        Player player = Bukkit.getPlayer(playerId);
        if (error != null || hold == null || (hold.status() != HoldResult.Status.SUCCESS
                && hold.status() != HoldResult.Status.DUPLICATE) || hold.hold().isEmpty()) {
            pendingPurchases.remove(playerId);
            if (player != null) messages.send(player, hold != null && hold.status() == HoldResult.Status.INSUFFICIENT_FUNDS
                    ? "insufficient-funds" : "purchase-failed");
            return;
        }
        HoldSnapshot reserved = hold.hold().orElseThrow();
        GuildTraderDefinition trader = repository.get(traderId);
        GuildBonusOffer offer = trader == null ? null : trader.offers().get(slot);
        Optional<AurumGuildsHook.Membership> membership = guilds.membership(playerId);
        if (offer == null || membership.isEmpty() || membership.get().guildId() != guildId) {
            releaseReservation(reserved, playerId, "guild-membership-changed");
            return;
        }
        if (player == null || !delivery.available()) {
            // Записать долг некуда (или некому его вручить). Выдавать бонус и
            // надеяться — ровно то, от чего заявки и избавляют.
            releaseReservation(reserved, playerId, "delivery-unavailable");
            return;
        }

        BonusClaim bonus = new BonusClaim(reserved.idempotencyKey(), traderId, slot, guildId,
                offer.type(), offer.magnitude(), duration == null ? 0L : duration.toSeconds(), actor);
        ClaimRequest request;
        try {
            request = new ClaimRequest("npc-guild-claim:" + reserved.idempotencyKey(), ClaimGateway.PLUGIN,
                    playerId, GuildTraderPlan.KIND, bonus.stepCount(), bonus.summary(), bonus.encode());
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().warning("Could not describe guild bonus as a claim: " + invalid.getMessage());
            releaseReservation(reserved, playerId, "purchase-failed");
            return;
        }

        String openTrader = traderId;
        delivery.promise(request).whenComplete((promised, failure) -> runMain(() -> {
            pendingPurchases.remove(playerId);
            Player online = Bukkit.getPlayer(playerId);
            if (failure != null || promised == null || !promised.ok() || promised.claim().isEmpty()) {
                releaseReservation(reserved, playerId, "purchase-failed");
                return;
            }
            if (online == null) return;
            delivery.deliver(online, promised.claim().orElseThrow(),
                    new GuildTraderPlan(plugin, economy, messages, guilds, delivery, bonus));
            refresh(online, openTrader);
        }));
    }

    /** Отпустить резерв: деньги не двигались, отменять нечего кроме сообщения. */
    private void releaseReservation(HoldSnapshot hold, UUID playerId, String message) {
        economy.release(hold).whenComplete((ignored, error) -> runMain(() -> {
            pendingPurchases.remove(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) messages.send(player, message);
        }));
    }

    /** Перерисовать окно торговца, если игрок всё ещё в нём стоит. */
    private void refresh(Player player, String traderId) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof GuildTraderHolder holder
                && holder.traderId.equals(traderId)) {
            open(player, traderId);
        }
    }

    private void completeFreePurchase(UUID playerId, String traderId,
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
                if (player != null) messages.send(player, "guild-purchase-failed",
                        Map.of("reason", result == null ? messages.text("messages.unknown-error") : result.message()));
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

    private void runMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run(); else Bukkit.getScheduler().runTask(plugin, action);
    }

    private Map<String, Object> placeholders(GuildBonusOffer offer, AurumGuildsHook.Membership membership,
                                              AurumGuildsHook.ActiveBonus current) {
        return Map.of(
                "guild", membership.guildName(),
                "guild_tag", membership.guildTag(),
                "bonus", messages.bonusTitle(offer.type()),
                "bonus_type", offer.type().name().toLowerCase(),
                "bonus_value", messages.bonusDescription(offer.type(), offer.magnitude()),
                "magnitude", number(offer.magnitude()),
                "duration", offer.permanent() ? messages.text("time.forever") : messages.duration(Duration.ofSeconds(offer.durationSeconds())),
                "price", economy.format(offer.price()),
                "current_value", current == null ? messages.text("common.none") : messages.bonusDescription(current.type(), current.magnitude()),
                "current_remaining", current == null ? messages.text("common.none") : current.permanent()
                        ? messages.text("time.forever") : messages.duration(current.remaining(Instant.now())));
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
