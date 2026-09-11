package dev.addons.npc.service;

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
import ovh.aurumgg.core.api.EconomyMode;
import ovh.aurumgg.core.api.HoldRequest;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.TransactionCategory;

/** Native AurumCore writer with Vault retained only for primary-currency display compatibility. */
public final class EconomyService {
    private final JavaPlugin plugin;
    private Economy vault;
    private volatile AurumEconomyApi aurum;
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
        } catch (LinkageError error) {
            aurum = null;
            plugin.getLogger().warning("AurumCore API could not be linked: " + error.getClass().getSimpleName());
        }
        return aurum != null;
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

    public CompletionStage<HoldResult> reserve(String key, AccountId from, AccountId to, double amount,
                                                TransactionCategory category, String purpose,
                                                String reference, Map<String, String> metadata) {
        AurumEconomyApi current = aurum;
        if (current == null) return unavailable();
        long configured = plugin.getConfig().getLong("economy.hold-ttl-seconds", 300L);
        long ttl = Math.max(10L, Math.min(3600L, configured));
        try {
            HoldRequest request = new HoldRequest(key, from, to, currencyId(), amount(amount), category,
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

    /** Release a reservation the plugin never wrote a saga for. */
    public CompletionStage<HoldResult> release(ovh.aurumgg.core.api.HoldSnapshot hold) {
        AurumEconomyApi current = aurum;
        return current == null ? unavailable() : current.releaseHold(hold.id());
    }

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
