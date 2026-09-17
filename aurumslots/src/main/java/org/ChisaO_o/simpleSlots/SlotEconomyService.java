package org.ChisaO_o.simpleSlots;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.AurumAccountRegistryApi;
import ovh.aurumgg.core.api.EconomyMode;
import ovh.aurumgg.core.api.ManagedAccountCloseRequest;
import ovh.aurumgg.core.api.ManagedAccountMember;
import ovh.aurumgg.core.api.ManagedAccountMutationResult;
import ovh.aurumgg.core.api.ManagedAccountRegistration;
import ovh.aurumgg.core.api.ManagedAccountStatus;
import ovh.aurumgg.core.api.HoldRequest;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.HoldSnapshot;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;

final class SlotEconomyService implements Listener {
    private final SimpleSlots plugin;
    private final SpinJournal journal;
    private final Set<UUID> recovering = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> activeSpins = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile AurumEconomyApi api;
    private volatile AurumAccountRegistryApi accounts;

    SlotEconomyService(SimpleSlots plugin, SpinJournal journal) {
        this.plugin = plugin;
        this.journal = journal;
    }

    boolean hook() {
        try {
            RegisteredServiceProvider<AurumEconomyApi> registration = plugin.getServer().getServicesManager()
                    .getRegistration(AurumEconomyApi.class);
            AurumEconomyApi selected = registration == null ? null : registration.getProvider();
            api = selected != null && selected.mode() == EconomyMode.ACTIVE ? selected : null;
        } catch (LinkageError error) {
            api = null;
            plugin.getLogger().warning("AurumCore API could not be linked: " + error.getClass().getSimpleName());
        }
        hookAccounts();
        return api != null;
    }

    private boolean hookAccounts() {
        try {
            RegisteredServiceProvider<AurumAccountRegistryApi> registration = plugin.getServer().getServicesManager()
                    .getRegistration(AurumAccountRegistryApi.class);
            accounts = registration == null ? null : registration.getProvider();
        } catch (LinkageError error) {
            accounts = null;
        }
        return accounts != null;
    }

    boolean managedAccountsAvailable() { return accounts != null || hookAccounts(); }

    void synchronizeMachine(SlotMachine machine, String destination) {
        if (!managedAccountsAvailable() || machine.closing) return;
        String expectedAccount = machine.accountReference();
        accounts.synchronize(machineRegistration(machine, destination))
                .thenAccept(result -> {
                    if (isRetired(result)) {
                        plugin.rotateRetiredMachineAccount(machine, expectedAccount);
                        return;
                    }
                    if (result.status() != ManagedAccountMutationResult.Status.SUCCESS
                            && result.status() != ManagedAccountMutationResult.Status.DUPLICATE) {
                        plugin.getLogger().warning("Could not synchronize managed account for slot machine "
                                + machine.id + ": " + result.message());
                    }
                });
    }

    void closeMachine(SlotMachine machine, String destination, String actor,
                      BiConsumer<Boolean, String> completion) {
        closeMachineResult(machine, destination, actor).whenComplete((result, failure) -> {
            if (failure != null || result == null) {
                completion.accept(false, "AurumCore unavailable");
                return;
            }
            boolean success = result.status() == ManagedAccountMutationResult.Status.SUCCESS
                    || result.status() == ManagedAccountMutationResult.Status.DUPLICATE;
            completion.accept(success, result.message());
        });
    }

    private CompletionStage<ManagedAccountMutationResult> closeMachineResult(SlotMachine machine,
                                                                              String destination, String actor) {
        if (!managedAccountsAvailable()) return CompletableFuture.completedFuture(
                new ManagedAccountMutationResult(ManagedAccountMutationResult.Status.UNAVAILABLE, null,
                        "AurumCore account registry is unavailable"));
        String profileKey = profileKey(machine);
        return accounts.find(profileKey).thenCompose(existing -> {
            if (existing.isPresent()) return closeMachineProfile(profileKey, destination, actor);
            return accounts.synchronize(machineRegistration(machine, destination)).thenCompose(created -> {
                if (created.status() != ManagedAccountMutationResult.Status.SUCCESS
                        && created.status() != ManagedAccountMutationResult.Status.DUPLICATE) {
                    return CompletableFuture.completedFuture(created);
                }
                return closeMachineProfile(profileKey, destination, actor);
            });
        });
    }

    private CompletionStage<ManagedAccountMutationResult> closeMachineProfile(String profileKey,
                                                                                String destination, String actor) {
        return accounts.close(new ManagedAccountCloseRequest(UUID.randomUUID().toString(), profileKey,
                destination, actor, "delete slot machine"));
    }

    private ManagedAccountRegistration machineRegistration(SlotMachine machine, String destination) {
        String resolvedDestination = destination == null || destination.isBlank()
                ? "treasury:global" : destination.toLowerCase(Locale.ROOT);
        String accountReference = machine.accountReference();
        String revision = UUID.nameUUIDFromBytes((machine.id + "\u0000" + accountReference + "\u0000"
                + machine.founderUuid + "\u0000"
                + resolvedDestination).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return new ManagedAccountRegistration(
                "slots-account-sync:" + machine.id + ":" + revision, profileKey(machine), "SLOTS",
                machine.id, "Slot machine bankroll", "SERVER", "global", machine.founderUuid,
                "AurumSlots", "SLOTS", machine.id, resolvedDestination, false,
                List.of(new ManagedAccountMember(new AccountId(AccountType.SLOTS, accountReference), "primary", 0)),
                "system:AurumSlots", "synchronize slot machine account");
    }

    private static String profileKey(SlotMachine machine) {
        return "slots:" + machine.accountReference();
    }

    static boolean isRetired(ManagedAccountMutationResult result) {
        return result != null && result.account() != null
                && isRetired(result.account().status());
    }

    static boolean isRetired(ManagedAccountStatus status) {
        return status == ManagedAccountStatus.CLOSING || status == ManagedAccountStatus.CLOSED;
    }

    boolean hasPending(String machineId) {
        return journal.all().stream().anyMatch(record -> record.machineId().equals(machineId));
    }

    boolean available() { return api != null; }
    boolean isProvider(Object provider) { return api == provider; }
    BigDecimal amount(double value) {
        int scale = api == null ? 2 : api.primaryCurrency().scale();
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    void requestSpin(Player player, SlotMachine machine) {
        if (machine.paymentPending) {
            player.sendMessage(plugin.getMsg("payment_pending"));
            return;
        }
        machine.paymentPending = true;
        UUID operation = UUID.randomUUID();
        AccountId payoutSource = payoutSource(machine);
        AurumEconomyApi current = api;
        if (current == null) {
            machine.paymentPending = false;
            player.sendMessage(plugin.getMsg("payment_failed"));
            return;
        }
        if (payoutSource.type() == AccountType.SYSTEM_SOURCE) {
            reserveBet(player, machine, operation, payoutSource);
            return;
        }
        BigDecimal required = requiredBankroll(machine);
        current.balance(payoutSource, current.primaryCurrency().id()).whenComplete((balance, error) ->
                onMain(() -> {
                    if (error != null || balance == null || balance.isEmpty()) {
                        machine.paymentPending = false;
                        player.sendMessage(plugin.getMsg("bankroll_check_failed"));
                        return;
                    }
                    if (balance.get().balance().compareTo(required) < 0) {
                        machine.paymentPending = false;
                        player.sendMessage(plugin.getMsg("bankroll_insufficient")
                                .replace("%required%", required.stripTrailingZeros().toPlainString())
                                .replace("%source%", payoutSource.stableKey()));
                        return;
                    }
                    reserveBet(player, machine, operation, payoutSource);
                }));
    }

    private void reserveBet(Player player, SlotMachine machine, UUID operation, AccountId payoutSource) {
        reserve(operation, player.getUniqueId(), machine.id, machine.accountReference(), machine.bet)
                .whenComplete((result, error) ->
                onMain(() -> handleReserved(player, machine, operation, payoutSource, result, error)));
    }

    String payoutSourceKey(SlotMachine machine) {
        return payoutSource(machine).stableKey();
    }

    private AccountId payoutSource(SlotMachine machine) {
        return switch (plugin.payoutMode(machine)) {
            case MACHINE, DEFAULT -> new AccountId(AccountType.SLOTS, machine.accountReference());
            case TREASURY -> new AccountId(AccountType.TREASURY, plugin.payoutTreasury(machine));
            case LEGACY -> new AccountId(AccountType.SYSTEM_SOURCE, "slot-payouts");
        };
    }

    private BigDecimal requiredBankroll(SlotMachine machine) {
        return amount(machine.bet * plugin.bankrollCheckMultiplier());
    }

    private void handleReserved(Player player, SlotMachine machine, UUID operation,
                                AccountId payoutSource, HoldResult result, Throwable error) {
        if (error != null || result == null || (result.status() != HoldResult.Status.SUCCESS
                && result.status() != HoldResult.Status.DUPLICATE) || result.hold().isEmpty()
                || result.hold().get().status() != HoldSnapshot.Status.HELD) {
            machine.paymentPending = false;
            if (result != null && result.status() == HoldResult.Status.INSUFFICIENT_FUNDS) {
                player.sendMessage(plugin.getMsg("not_enough_money"));
            } else {
                player.sendMessage(plugin.getMsg("payment_failed"));
                if (error != null) plugin.getLogger().log(Level.WARNING, "Could not reserve slot bet", error);
            }
            return;
        }
        SpinRecord record = SpinRecord.accepted(operation, player.getUniqueId(), machine.id,
                result.hold().get(), payoutSource);
        if (!player.isOnline() || !plugin.isCurrentMachine(machine)) {
            machine.paymentPending = false;
            release(record);
            return;
        }
        try {
            record = journal.begin(operation, player.getUniqueId(), machine.id,
                    result.hold().get(), payoutSource);
        } catch (RuntimeException journalError) {
            machine.paymentPending = false;
            release(record);
            player.sendMessage(plugin.getMsg("payment_failed"));
            plugin.getLogger().log(Level.SEVERE, "Could not persist slot transaction", journalError);
            return;
        }
        SpinRecord persisted = record;
        capture(record).whenComplete((capture, failure) ->
                onMain(() -> handleCaptured(player, machine, persisted, capture, failure)));
    }

    private void handleCaptured(Player player, SlotMachine machine, SpinRecord record,
                                HoldResult result, Throwable error) {
        machine.paymentPending = false;
        if (error == null && result != null && (result.status() == HoldResult.Status.SUCCESS
                || result.status() == HoldResult.Status.DUPLICATE) && result.hold().isPresent()
                && result.hold().get().status() == HoldSnapshot.Status.CAPTURED) {
            if (!player.isOnline() || !plugin.isCurrentMachine(machine) || machine.isSpinning) {
                refund(record).whenComplete((ignored, failure) -> recover());
                if (player.isOnline() && machine.isSpinning) player.sendMessage(plugin.getMsg("spinning"));
                return;
            }
            markActive(record);
            plugin.startMoneySpin(machine, player, record);
            return;
        }
        if (result != null && result.status() != HoldResult.Status.UNAVAILABLE) {
            release(record).whenComplete((released, failure) -> {
                if (failure == null && released != null && released.status() != HoldResult.Status.UNAVAILABLE) {
                    journal.complete(record);
                }
            });
        }
        player.sendMessage(plugin.getMsg("payment_failed"));
        if (error != null) plugin.getLogger().log(Level.WARNING, "Could not capture slot bet", error);
    }

    void complete(SpinRecord record) {
        markInactive(record);
        journal.complete(record);
    }

    void abort(SpinRecord record) {
        markInactive(record);
        recover();
    }

    void payWinnings(SpinRecord record, double winnings, Consumer<BigDecimal> success) {
        SpinRecord pending;
        try {
            pending = journal.payout(record, amount(winnings));
            markInactive(record);
        } catch (RuntimeException error) {
            markInactive(record);
            recover();
            plugin.getLogger().log(Level.SEVERE, "Could not persist pending slot payout", error);
            Player target = Bukkit.getPlayer(record.playerId());
            if (target != null) target.sendMessage(plugin.getMsg("payout_pending"));
            return;
        }
        payout(pending).whenComplete((result, error) -> onMain(() -> {
            if (error == null && result != null && (result.status() == TransactionResult.Status.SUCCESS
                    || result.status() == TransactionResult.Status.DUPLICATE)) {
                journal.complete(pending);
                success.accept(result.netAmount());
            } else {
                Player target = Bukkit.getPlayer(record.playerId());
                if (target != null) target.sendMessage(plugin.getMsg("payout_pending"));
                if (error != null) plugin.getLogger().log(Level.SEVERE, "Slot payout is pending recovery", error);
            }
        }));
    }

    CompletionStage<HoldResult> reserve(UUID operation, UUID playerId, String machineId,
                                        String machineAccountReference, double bet) {
        AurumEconomyApi current = api;
        if (current == null) return unavailableHold();
        long configured = plugin.getConfig().getLong("economy.hold-ttl-seconds", 300L);
        long ttl = Math.max(10L, Math.min(3600L, configured));
        Map<String, String> metadata = metadata(operation, machineId);
        try {
            HoldRequest request = new HoldRequest("slots-bet:" + operation, AccountId.player(playerId),
                    new AccountId(AccountType.SLOTS, machineAccountReference),
                    current.primaryCurrency().id(), amount(bet),
                    TransactionCategory.SLOT_BET, "slot-spin", machineId,
                    Instant.now().plusSeconds(ttl), metadata);
            return current.createHold(request);
        } catch (IllegalArgumentException error) {
            return CompletableFuture.completedFuture(new HoldResult(HoldResult.Status.REJECTED,
                    Optional.empty(), "Invalid bet precision"));
        }
    }

    CompletionStage<HoldResult> capture(SpinRecord record) {
        AurumEconomyApi current = api;
        return current == null ? unavailableHold() : current.captureHold(record.holdId(), record.captureRequest());
    }

    CompletionStage<HoldResult> release(SpinRecord record) {
        AurumEconomyApi current = api;
        return current == null ? unavailableHold() : current.releaseHold(record.holdId());
    }

    CompletionStage<TransactionResult> payout(SpinRecord record) {
        return credit(record, record.payoutSource(), record.payout(), TransactionCategory.SLOT_PAYOUT,
                "slots-payout:" + record.operationId());
    }

    CompletionStage<TransactionResult> refund(SpinRecord record) {
        // A refund returns the captured bet from the machine that received it;
        // it must never mint from the configured payout source.
        return credit(record, record.machineAccount(),
                record.reservedDebit(), TransactionCategory.REFUND,
                "slots-refund:" + record.operationId());
    }

    void markActive(SpinRecord record) { activeSpins.add(record.operationId()); }
    void markInactive(SpinRecord record) { activeSpins.remove(record.operationId()); }

    private CompletionStage<TransactionResult> credit(SpinRecord record, AccountId source, BigDecimal amount,
                                                       TransactionCategory category, String key) {
        AurumEconomyApi current = api;
        if (current == null) return unavailableTransaction(key, amount);
        TransactionRequest request = new TransactionRequest(key,
                source, AccountId.player(record.playerId()),
                record.currencyId(), amount, category, record.metadata());
        return current.transfer(request);
    }

    void recover() {
        if (!available() && !hook()) return;
        for (SpinRecord record : journal.all()) {
            if (activeSpins.contains(record.operationId())) continue;
            if (!recovering.add(record.operationId())) continue;
            if (record.state() == SpinRecord.State.PAYOUT_PENDING) {
                payout(record).whenComplete((result, error) -> finishRecovery(record, result, error));
                continue;
            }
            AurumEconomyApi current = api;
            if (current == null) { recovering.remove(record.operationId()); continue; }
            current.hold(record.holdKey()).whenComplete((hold, error) -> {
                if (error != null || hold == null || hold.isEmpty()) {
                    recovering.remove(record.operationId()); return;
                }
                HoldSnapshot snapshot = hold.get();
                if (snapshot.status() == HoldSnapshot.Status.CAPTURED) {
                    refund(record).whenComplete((result, failure) -> finishRecovery(record, result, failure));
                } else if (snapshot.status() == HoldSnapshot.Status.HELD) {
                    release(record).whenComplete((result, failure) -> finishRelease(record, result, failure));
                } else {
                    journal.complete(record); recovering.remove(record.operationId()); warned.remove(record.operationId());
                }
            });
        }
    }

    void unavailable() {
        api = null;
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == AurumAccountRegistryApi.class) {
            hookAccounts();
            plugin.synchronizeMachineAccounts();
            return;
        }
        if (available() || event.getProvider().getService() != AurumEconomyApi.class) return;
        if (hook()) {
            plugin.getLogger().info("AurumCore economy became available.");
            plugin.refreshPaymentDisplay();
            recover();
            plugin.synchronizeMachineAccounts();
        }
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == AurumAccountRegistryApi.class
                && accounts == event.getProvider().getProvider()) {
            accounts = null;
            return;
        }
        if (!available() || event.getProvider().getService() != AurumEconomyApi.class
                || !isProvider(event.getProvider().getProvider())) return;
        plugin.getLogger().warning("AurumCore economy was unregistered; payments and pending recovery are paused.");
        unavailable();
        plugin.refreshPaymentDisplay();
    }

    private void onMain(Runnable task) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private void finishRecovery(SpinRecord record, TransactionResult result, Throwable error) {
        recovering.remove(record.operationId());
        if (error == null && result != null && (result.status() == TransactionResult.Status.SUCCESS
                || result.status() == TransactionResult.Status.DUPLICATE)) {
            journal.complete(record); warned.remove(record.operationId()); return;
        }
        if (warned.add(record.operationId())) {
            String status = result == null ? "no result" : result.status() + ": " + result.message();
            plugin.getLogger().log(Level.SEVERE,
                    "Could not recover slot transaction " + record.operationId() + " (" + status + ")", error);
        }
    }

    private void finishRelease(SpinRecord record, HoldResult result, Throwable error) {
        recovering.remove(record.operationId());
        if (error == null && result != null && result.status() != HoldResult.Status.UNAVAILABLE) {
            journal.complete(record); warned.remove(record.operationId());
        }
    }

    private static Map<String, String> metadata(UUID operation, String machineId) {
        return Map.of("plugin", "AurumSlots", "operation", operation.toString(), "machine", machineId);
    }
    private static CompletionStage<HoldResult> unavailableHold() {
        return CompletableFuture.completedFuture(new HoldResult(HoldResult.Status.UNAVAILABLE,
                Optional.empty(), "AurumCore holds are unavailable"));
    }
    private static CompletionStage<TransactionResult> unavailableTransaction(String key, BigDecimal amount) {
        return CompletableFuture.completedFuture(new TransactionResult(TransactionResult.Status.UNAVAILABLE,
                key, amount, amount, BigDecimal.ZERO.setScale(amount.scale()), "AurumCore is unavailable"));
    }
}
