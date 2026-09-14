package dev.addons.npc.service;

import dev.addons.npc.model.BuyerBudgetMode;
import dev.addons.npc.model.BuyerDefinition;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.AurumAccountRegistryApi;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.EconomyMode;
import ovh.aurumgg.core.api.HoldRequest;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.ManagedAccountCloseRequest;
import ovh.aurumgg.core.api.ManagedAccountMember;
import ovh.aurumgg.core.api.ManagedAccountMutationResult;
import ovh.aurumgg.core.api.ManagedAccountRegistration;

/** Native AurumCore writer with Vault retained only for primary-currency display compatibility. */
public final class EconomyService {
    private final JavaPlugin plugin;
    private Economy vault;
    private volatile AurumEconomyApi aurum;
    private volatile AurumAccountRegistryApi accounts;
    private final java.util.Set<java.util.UUID> warnedRecovery = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public EconomyService(JavaPlugin plugin) { this.plugin = plugin; }

    public boolean hook() {
        RegisteredServiceProvider<Economy> vaultRegistration = plugin.getServer().getServicesManager()
                .getRegistration(Economy.class);
        vault = vaultRegistration == null ? null : vaultRegistration.getProvider();
        try {
            RegisteredServiceProvider<AurumEconomyApi> registration = plugin.getServer().getServicesManager()
                    .getRegistration(AurumEconomyApi.class);
            AurumEconomyApi selected = registration == null ? null : registration.getProvider();
            aurum = selected != null && selected.mode() == EconomyMode.ACTIVE ? selected : null;
            RegisteredServiceProvider<AurumAccountRegistryApi> accountRegistration = plugin.getServer()
                    .getServicesManager().getRegistration(AurumAccountRegistryApi.class);
            accounts = accountRegistration == null ? null : accountRegistration.getProvider();
        } catch (LinkageError error) {
            aurum = null;
            accounts = null;
            plugin.getLogger().warning("AurumCore API could not be linked: " + error.getClass().getSimpleName());
        }
        return aurum != null;
    }

    /** Paged, on-demand choices; never walks all players or polls the registry. */
    public CompletionStage<ovh.aurumgg.core.api.ManagedAccountPage> revenueAccounts(int offset) {
        var registry = accounts;
        if (registry == null) return CompletableFuture.failedFuture(new IllegalStateException("Account registry unavailable"));
        return registry.list(new ovh.aurumgg.core.api.ManagedAccountQuery("", "",
                ovh.aurumgg.core.api.ManagedAccountStatus.ACTIVE, false, offset, 20));
    }

    public CompletionStage<AccountId> revenueAccount(String profile, String role) {
        var registry = accounts;
        if (registry == null) return CompletableFuture.failedFuture(new IllegalStateException("Account registry unavailable"));
        return registry.find(profile).thenApply(found -> {
            var account = found.orElseThrow(() -> new IllegalArgumentException("Account not found"));
            if (account.technical() || account.status() != ovh.aurumgg.core.api.ManagedAccountStatus.ACTIVE)
                throw new IllegalArgumentException("Account is not active");
            return account.members().stream().filter(member -> member.role().equals(role))
                    .map(ManagedAccountMember::account).filter(EconomyService::revenueEligible)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Invalid account role"));
        });
    }

    public static boolean revenueEligible(AccountId account) {
        return switch (account.type()) {
            case SYSTEM_SOURCE, SYSTEM_SINK, TRADE_ESCROW, EXCHANGE_RESERVE -> false;
            default -> true;
        };
    }

    public CompletionStage<AccountId> shopRevenue(dev.addons.npc.model.ShopDefinition shop) {
        return revenueAccount(shop.revenueProfile(), shop.revenueRole());
    }

    public CompletionStage<HoldResult> reserve(String key, AccountId from, CompletionStage<AccountId> destination,
            double value, TransactionCategory category, String purpose, String reference, Map<String, String> metadata) {
        return destination.thenCompose(to -> reserve(key, from, to, value, category, purpose, reference, metadata))
                .exceptionally(error -> new HoldResult(HoldResult.Status.REJECTED, Optional.empty(),
                        "Revenue account unavailable"));
    }

    public boolean available() { return aurum != null; }
    public double balance(OfflinePlayer player) { return vault == null ? 0 : vault.getBalance(player); }
    public String format(double amount) { return vault == null ? String.format("%.2f", amount) : vault.format(amount); }
    public String currencyId() {
        AurumEconomyApi current = aurum;
        return current == null ? "coins" : current.primaryCurrency().id();
    }
    public BigDecimal amount(double value) {
        AurumEconomyApi current = aurum;
        int scale = current == null ? 2 : current.primaryCurrency().scale();
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    /** The real finite source used for a buyer payout. Legacy minting is explicit only. */
    public AccountId buyerBudgetSource(BuyerDefinition buyer) {
        BuyerBudgetMode mode = buyer.budgetMode();
        if (mode == BuyerBudgetMode.DEFAULT) {
            try {
                mode = BuyerBudgetMode.parse(plugin.getConfig().getString(
                        "economy.buyer-budget.mode", "BUYER"));
            } catch (IllegalArgumentException invalid) {
                mode = BuyerBudgetMode.BUYER;
            }
        }
        return switch (mode) {
            case DEFAULT, BUYER -> new AccountId(AccountType.NPC_BUYER, buyer.id());
            case TREASURY -> new AccountId(AccountType.TREASURY,
                    buyer.budgetTreasuryId().isBlank() ? defaultBuyerTreasury() : buyer.budgetTreasuryId());
            case LEGACY -> new AccountId(AccountType.SYSTEM_SOURCE, "npc-buyers");
        };
    }

    public CompletionStage<ManagedAccountMutationResult> synchronizeBuyer(BuyerDefinition buyer) {
        AurumAccountRegistryApi registry = accounts;
        if (registry == null) return unavailableAccount("AurumCore account registry is unavailable");
        String destination = buyerCloseDestination();
        String source = buyerBudgetSource(buyer).stableKey();
        String revision = java.util.UUID.nameUUIDFromBytes((buyer.id() + "\u0000" + source + "\u0000"
                + destination).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return registry.synchronize(new ManagedAccountRegistration(
                "npc-buyer-sync:" + buyer.id() + ":" + revision, "npc-buyer:" + buyer.id(),
                "NPC_BUYER", buyer.id(), "NPC buyer operating budget", "SERVER", "global", "",
                "AddonsNPC", "NPC_BUYER", buyer.id(), destination, false,
                java.util.List.of(new ManagedAccountMember(
                        new AccountId(AccountType.NPC_BUYER, buyer.id()), "primary", 0)),
                "system:AddonsNPC", "synchronize NPC buyer account"));
    }

    public CompletionStage<ManagedAccountMutationResult> closeBuyer(BuyerDefinition buyer, String actor) {
        AurumAccountRegistryApi registry = accounts;
        if (registry == null) return unavailableAccount("AurumCore account registry is unavailable");
        String profile = "npc-buyer:" + buyer.id();
        return registry.find(profile).thenCompose(existing -> {
            if (existing.isPresent()
                    && existing.orElseThrow().status() == ovh.aurumgg.core.api.ManagedAccountStatus.CLOSED) {
                return CompletableFuture.completedFuture(new ManagedAccountMutationResult(
                        ManagedAccountMutationResult.Status.DUPLICATE, existing.orElseThrow(), "already-closed"));
            }
            CompletionStage<ManagedAccountMutationResult> ready = existing.isPresent()
                    ? CompletableFuture.completedFuture(new ManagedAccountMutationResult(
                            ManagedAccountMutationResult.Status.SUCCESS, existing.orElseThrow(), "found"))
                    : synchronizeBuyer(buyer);
            return ready.thenCompose(synchronizedAccount -> {
                if (synchronizedAccount.status() != ManagedAccountMutationResult.Status.SUCCESS
                        && synchronizedAccount.status() != ManagedAccountMutationResult.Status.DUPLICATE) {
                    return CompletableFuture.completedFuture(synchronizedAccount);
                }
                return registry.close(new ManagedAccountCloseRequest(java.util.UUID.randomUUID().toString(),
                        profile, buyerCloseDestination(), actor, "delete NPC buyer"));
            });
        });
    }

    private String defaultBuyerTreasury() {
        return validTreasury(plugin.getConfig().getString(
                "economy.buyer-budget.treasury-id", "global"), "global");
    }

    private String buyerCloseDestination() {
        return "treasury:" + validTreasury(plugin.getConfig().getString(
                "economy.buyer-account-close-destination.id", "global"), "global");
    }

    private static String validTreasury(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return value.matches("[a-z0-9][a-z0-9._:-]{0,63}") ? value : fallback;
    }

    private static CompletionStage<ManagedAccountMutationResult> unavailableAccount(String message) {
        return CompletableFuture.completedFuture(new ManagedAccountMutationResult(
                ManagedAccountMutationResult.Status.UNAVAILABLE, null, message));
    }

    public CompletionStage<HoldResult> reserve(String key, AccountId from, AccountId to, double amount,
                                                TransactionCategory category, String purpose,
                                                String reference, Map<String, String> metadata) {
        return reserve(key, from, to, amount(amount), category, purpose, reference, metadata);
    }

    public CompletionStage<HoldResult> reserve(String key, AccountId from, AccountId to, BigDecimal amount,
                                                TransactionCategory category, String purpose,
                                                String reference, Map<String, String> metadata) {
        AurumEconomyApi current = aurum;
        if (current == null) return unavailable();
        long configured = plugin.getConfig().getLong("economy.hold-ttl-seconds", 300L);
        long ttl = Math.max(10L, Math.min(3600L, configured));
        try {
            HoldRequest request = new HoldRequest(key, from, to, currencyId(), amount, category,
                    purpose, reference, Instant.now().plusSeconds(ttl), metadata);
            return current.createHold(request);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(new HoldResult(HoldResult.Status.REJECTED,
                    Optional.empty(), "Amount is below the currency precision or the hold request is invalid"));
        }
    }

    public CompletionStage<HoldResult> capture(NpcSaga saga) {
        AurumEconomyApi current = aurum;
        return current == null ? unavailable() : current.captureHold(saga.holdId(), saga.captureRequest());
    }

    /**
     * Look a hold up by the key the claim remembers.
     *
     * <p>Delivery captures from the snapshot Core returns, never from values it
     * kept itself: Core refuses a capture whose from, to, currency, amount,
     * category or metadata differ from the reservation by even one entry, and a
     * refusal AFTER the player's money is reserved is the worst possible moment
     * to discover a mismatch.
     */
    public CompletionStage<java.util.Optional<ovh.aurumgg.core.api.HoldSnapshot>> hold(String key) {
        AurumEconomyApi current = aurum;
        return current == null
                ? CompletableFuture.completedFuture(java.util.Optional.empty())
                : current.hold(key);
    }

    /** Capture exactly what was reserved, under a key stable for this claim. */
    public CompletionStage<HoldResult> capture(ovh.aurumgg.core.api.HoldSnapshot hold, String key) {
        AurumEconomyApi current = aurum;
        if (current == null) return unavailable();
        return current.captureHold(hold.id(), new ovh.aurumgg.core.api.TransactionRequest(
                key, hold.from(), hold.to(), hold.currency().id(), hold.amount(), hold.category(),
                hold.metadata()));
    }

    public CompletionStage<HoldResult> release(NpcSaga saga) {
        AurumEconomyApi current = aurum;
        return current == null ? unavailable() : current.releaseHold(saga.holdId());
    }

    /**
     * Pay a player from a system source, once.
     *
     * <p>No reservation: a buyer pays from {@code SYSTEM_SOURCE:npc-buyers},
     * and the ledger treats a system source as always funded, so there is
     * nothing to prove in advance. The idempotency key is what makes a repeat
     * safe, and unlike a hold it does not expire — which matters, because the
     * repeat may happen a restart later.
     */
    public CompletionStage<ovh.aurumgg.core.api.TransactionResult> pay(
            String key, AccountId from, AccountId to, BigDecimal amount,
            TransactionCategory category, Map<String, String> metadata) {
        AurumEconomyApi current = aurum;
        if (current == null) {
            return CompletableFuture.completedFuture(new ovh.aurumgg.core.api.TransactionResult(
                    ovh.aurumgg.core.api.TransactionResult.Status.UNAVAILABLE, key,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "AurumCore is unavailable"));
        }
        return current.transfer(new ovh.aurumgg.core.api.TransactionRequest(
                key, from, to, currencyId(), amount, category, metadata));
    }

    /** Release a reservation the plugin never wrote a saga for. */
    public CompletionStage<HoldResult> release(ovh.aurumgg.core.api.HoldSnapshot hold) {
        AurumEconomyApi current = aurum;
        return current == null ? unavailable() : current.releaseHold(hold.id());
    }

    /**
     * Дочистить операции, застрявшие на момент обновления до 2.0.0.
     *
     * Новых записей в журнале не появляется: всё, что начинается сейчас, держит
     * AurumCore заявкой. Здесь разбираются только те, что были начаты старой
     * версией, — по тому же правилу, по которому она их и разбирала.
     *
     * Гильдейский бонус — единственный случай, где «применено ли» приходится
     * опознавать по подписи `guild-actor` на самом бонусе. Ровно из-за
     * ненадёжности этого приёма (истёкший бонус выглядит невыданным) гильдейские
     * торговцы и переведены на заявки; здесь он остаётся лишь потому, что
     * старые записи другого способа не оставили.
     */
    public void recover(NpcSagaRepository sagas, AurumGuildsHook guilds) {
        if (!available() && !hook()) return;
        for (NpcSaga saga : sagas.all()) {
            NpcSaga selected = saga;
            if (saga.state() == NpcSaga.State.HELD && saga.kind() == NpcSaga.Kind.GUILD_BONUS
                    && guildBonusApplied(saga, guilds)) {
                try { selected = sagas.markApplied(saga); }
                catch (RuntimeException error) {
                    plugin.getLogger().log(Level.SEVERE, "Could not advance recovered NPC saga " + saga.id(), error);
                    continue;
                }
            }
            NpcSaga operation = selected;
            CompletionStage<HoldResult> future = operation.state() == NpcSaga.State.APPLIED
                    ? capture(operation) : release(operation);
            future.whenComplete((result, error) -> {
                if (error != null || result == null || result.status() == HoldResult.Status.UNAVAILABLE) return;
                if (result.status() == HoldResult.Status.SUCCESS || result.status() == HoldResult.Status.DUPLICATE
                        || operation.state() == NpcSaga.State.HELD) {
                    try { sagas.complete(operation); warnedRecovery.remove(operation.id()); }
                    catch (RuntimeException failure) {
                        plugin.getLogger().log(Level.SEVERE, "Could not clean recovered NPC saga " + operation.id(), failure);
                    }
                } else {
                    if (warnedRecovery.add(operation.id())) plugin.getLogger().severe("Applied NPC saga "
                            + operation.id() + " could not be captured: " + result.message());
                }
            });
        }
    }

    private static boolean guildBonusApplied(NpcSaga saga, AurumGuildsHook guilds) {
        try {
            long guildId = Long.parseLong(saga.metadata().getOrDefault("guild-id", "-1"));
            String actor = saga.metadata().getOrDefault("guild-actor", "");
            return guildId >= 0 && !actor.isBlank() && guilds.bonuses(guildId).stream()
                    .anyMatch(bonus -> actor.equals(bonus.grantedBy()));
        } catch (RuntimeException ignored) { return false; }
    }

    private static CompletionStage<HoldResult> unavailable() {
        return CompletableFuture.completedFuture(new HoldResult(HoldResult.Status.UNAVAILABLE,
                Optional.empty(), "AurumCore holds are unavailable"));
    }

}
