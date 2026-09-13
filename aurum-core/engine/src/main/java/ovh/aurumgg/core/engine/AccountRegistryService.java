package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AurumAccountRegistryApi;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.ManagedAccount;
import ovh.aurumgg.core.api.ManagedAccountCloseRequest;
import ovh.aurumgg.core.api.ManagedAccountMember;
import ovh.aurumgg.core.api.ManagedAccountMutationResult;
import ovh.aurumgg.core.api.ManagedAccountPage;
import ovh.aurumgg.core.api.ManagedAccountQuery;
import ovh.aurumgg.core.api.ManagedAccountRegistration;
import ovh.aurumgg.core.api.ManagedAccountStateRequest;
import ovh.aurumgg.core.api.ManagedAccountStatus;
import ovh.aurumgg.core.api.ManagedAccountTransferRequest;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;

/** Request-driven managed-account service; no timers, scans or copied balances. */
public final class AccountRegistryService implements AurumAccountRegistryApi {
    public static final String GLOBAL_TREASURY_PROFILE = "treasury:global";

    private final AccountRegistryRepository repository;
    private final MultiCurrencyEconomyService economy;
    private final Map<String, CurrencySpec> currencies;
    private final Executor executor;
    private final String defaultCloseDestination;
    @SuppressWarnings("unused") private final Clock clock;

    public AccountRegistryService(AccountRegistryRepository repository,
                                  MultiCurrencyEconomyService economy,
                                  Map<String, CurrencySpec> currencies,
                                  Executor executor,
                                  Clock clock,
                                  String defaultCloseDestination) {
        this.repository = repository;
        this.economy = economy;
        this.currencies = Map.copyOf(currencies);
        this.executor = executor;
        this.clock = clock;
        this.defaultCloseDestination = defaultCloseDestination == null || defaultCloseDestination.isBlank()
                ? GLOBAL_TREASURY_PROFILE : defaultCloseDestination.trim().toLowerCase(java.util.Locale.ROOT);
        if (this.defaultCloseDestination.length() > 191) {
            throw new IllegalArgumentException("Default close destination is too long");
        }
    }

    @Override
    public CompletionStage<ManagedAccountPage> list(ManagedAccountQuery query) {
        return supply(() -> repository.list(query, currencies));
    }

    @Override
    public CompletionStage<Optional<ManagedAccount>> find(String profileKey) {
        return supply(() -> repository.find(profileKey, currencies));
    }

    @Override
    public CompletionStage<ManagedAccountMutationResult> register(ManagedAccountRegistration request) {
        return supply(() -> {
            AccountRegistryRepository.WriteResult result = repository.register(request, hashRegistration(request));
            return result(result, repository.find(request.profileKey(), currencies).orElse(null));
        }).exceptionally(this::unavailable);
    }

    @Override
    public CompletionStage<ManagedAccountMutationResult> synchronize(ManagedAccountRegistration request) {
        return supply(() -> {
            AccountRegistryRepository.WriteResult result = repository.synchronize(request, hashRegistration(request));
            return result(result, repository.find(request.profileKey(), currencies).orElse(null));
        }).exceptionally(this::unavailable);
    }

    @Override
    public CompletionStage<ManagedAccountMutationResult> transfer(ManagedAccountTransferRequest request) {
        return supply(() -> transferBlocking(request)).exceptionally(this::unavailable);
    }

    private ManagedAccountMutationResult transferBlocking(ManagedAccountTransferRequest request) throws Exception {
        ManagedAccount from = repository.find(request.fromProfile(), currencies).orElse(null);
        ManagedAccount to = repository.find(request.toProfile(), currencies).orElse(null);
        if (from == null || to == null) return outcome(ManagedAccountMutationResult.Status.NOT_FOUND,
                null, from == null ? "source-profile-not-found" : "target-profile-not-found");
        if (from.status() != ManagedAccountStatus.ACTIVE || to.status() != ManagedAccountStatus.ACTIVE) {
            return outcome(ManagedAccountMutationResult.Status.REJECTED, from, "profile-not-active");
        }
        AccountId source = member(from, request.fromRole());
        AccountId target = member(to, request.toRole());
        if (source == null || target == null) {
            return outcome(ManagedAccountMutationResult.Status.NOT_FOUND, from, "member-role-not-found");
        }
        if (source.equals(target)) {
            return outcome(ManagedAccountMutationResult.Status.REJECTED, from, "same-ledger-account");
        }
        CurrencySpec currency = currencies.get(request.currencyId());
        if (currency == null) return outcome(ManagedAccountMutationResult.Status.REJECTED, from, "unknown-currency");
        TransactionResult transferred = economy.required(currency.id()).transferBlocking(new TransactionRequest(
                "account-transfer:" + hash(request.idempotencyKey()), source, target, currency.id(), request.amount(),
                TransactionCategory.TREASURY_TRANSFER, Map.of(
                        "actor", request.actor(), "reason", request.reason(),
                        "source-profile", from.profileKey(), "target-profile", to.profileKey())));
        ManagedAccount refreshed = repository.find(from.profileKey(), currencies).orElse(from);
        return switch (transferred.status()) {
            case SUCCESS -> outcome(ManagedAccountMutationResult.Status.SUCCESS, refreshed, "transferred");
            case DUPLICATE -> outcome(ManagedAccountMutationResult.Status.DUPLICATE, refreshed, "transferred");
            case REJECTED -> outcome(ManagedAccountMutationResult.Status.REJECTED, refreshed, transferred.message());
            case UNAVAILABLE -> outcome(ManagedAccountMutationResult.Status.UNAVAILABLE, refreshed, transferred.message());
        };
    }

    @Override
    public CompletionStage<ManagedAccountMutationResult> setFrozen(ManagedAccountStateRequest request) {
        return supply(() -> {
            ManagedAccountStatus target = request.frozen()
                    ? ManagedAccountStatus.FROZEN : ManagedAccountStatus.ACTIVE;
            AccountRegistryRepository.WriteResult write = repository.setStatus(
                    request.idempotencyKey(), hash("STATE", request.profileKey(), Boolean.toString(request.frozen()),
                            request.actor(), request.reason()),
                    request.profileKey(), request.frozen() ? ManagedAccountStatus.ACTIVE : ManagedAccountStatus.FROZEN,
                    target, target, request.frozen() ? "FREEZE" : "UNFREEZE", request.actor(), request.reason());
            return result(write, repository.find(request.profileKey(), currencies).orElse(null));
        }).exceptionally(this::unavailable);
    }

    @Override
    public CompletionStage<ManagedAccountMutationResult> close(ManagedAccountCloseRequest request) {
        return supply(() -> closeBlocking(request)).exceptionally(this::unavailable);
    }

    /** Called once after service registration; resumes at most 100 durable close plans. */
    public CompletionStage<Integer> resumePendingClosures() {
        return supply(() -> {
            int completed = 0;
            for (AccountRegistryRepository.PendingClose pending : repository.pendingClosures(100)) {
                ManagedAccountMutationResult result = closeBlocking(new ManagedAccountCloseRequest(
                        pending.idempotencyKey(), pending.profileKey(), pending.destinationProfile(),
                        pending.actor(), pending.reason()));
                if (result.status() == ManagedAccountMutationResult.Status.SUCCESS
                        || result.status() == ManagedAccountMutationResult.Status.DUPLICATE) completed++;
            }
            return completed;
        });
    }

    private ManagedAccountMutationResult closeBlocking(ManagedAccountCloseRequest request) throws Exception {
        ManagedAccount before = repository.find(request.profileKey(), currencies).orElse(null);
        if (before == null) return outcome(ManagedAccountMutationResult.Status.NOT_FOUND, null, "profile-not-found");
        if (before.profileKey().equals(GLOBAL_TREASURY_PROFILE)) {
            return outcome(ManagedAccountMutationResult.Status.REJECTED, before, "global-treasury-cannot-close");
        }
        if (before.status() == ManagedAccountStatus.CLOSING) {
            AccountRegistryRepository.PendingClose persisted = repository.pendingClosure(before.profileKey())
                    .orElse(null);
            if (persisted == null) {
                return outcome(ManagedAccountMutationResult.Status.CONFLICT, before, "close-plan-missing");
            }
            request = new ManagedAccountCloseRequest(persisted.idempotencyKey(), persisted.profileKey(),
                    persisted.destinationProfile(), persisted.actor(), persisted.reason());
        }
        String destinationKey = request.destinationProfile().isBlank()
                ? (before.closeDestinationProfile().isBlank()
                    ? defaultCloseDestination : before.closeDestinationProfile())
                : request.destinationProfile();
        if (destinationKey.equals(before.profileKey())) {
            return outcome(ManagedAccountMutationResult.Status.REJECTED, before, "close-destination-is-source");
        }
        ManagedAccount destination = repository.find(destinationKey, currencies).orElse(null);
        if (destination == null) return outcome(ManagedAccountMutationResult.Status.NOT_FOUND, before,
                "close-destination-not-found");
        if (destination.status() != ManagedAccountStatus.ACTIVE) {
            return outcome(ManagedAccountMutationResult.Status.REJECTED, before, "close-destination-not-active");
        }
        AccountId destinationAccount = member(destination, "primary");
        if (destinationAccount == null) return outcome(ManagedAccountMutationResult.Status.REJECTED, before,
                "close-destination-has-no-primary-member");

        String intent = hash("CLOSE", before.profileKey(), destinationKey, request.actor(), request.reason());
        AccountRegistryRepository.WriteResult begun = repository.beginClose(request.idempotencyKey(), intent,
                before.profileKey(), destinationKey, request.actor(), request.reason());
        if (begun.status() != AccountRegistryRepository.WriteStatus.SUCCESS
                && begun.status() != AccountRegistryRepository.WriteStatus.DUPLICATE) {
            return result(begun, repository.find(before.profileKey(), currencies).orElse(before));
        }
        ManagedAccount closing = repository.find(before.profileKey(), currencies).orElse(before);
        List<AccountId> members = closing.members().stream().map(ManagedAccountMember::account).toList();
        if (repository.hasUnresolvedHolds(members)) {
            return outcome(ManagedAccountMutationResult.Status.REJECTED, closing, "unresolved-holds");
        }

        int memberIndex = 0;
        for (ManagedAccountMember member : closing.members().stream()
                .sorted(Comparator.comparingInt(ManagedAccountMember::displayOrder)
                        .thenComparing(ManagedAccountMember::role)).toList()) {
            for (CurrencySpec currency : currencies.values().stream()
                    .sorted(Comparator.comparing(CurrencySpec::id)).toList()) {
                BigDecimal balance = economy.required(currency.id()).balanceBlocking(member.account());
                if (balance.signum() < 0) {
                    return outcome(ManagedAccountMutationResult.Status.REJECTED, closing, "negative-balance");
                }
                if (balance.signum() == 0) continue;
                String sweepKey = "account-close:" + hash(request.idempotencyKey(),
                        Integer.toString(memberIndex), currency.id());
                TransactionResult swept = economy.required(currency.id()).transferBlocking(new TransactionRequest(
                        sweepKey, member.account(), destinationAccount, currency.id(), balance,
                        TransactionCategory.ACCOUNT_CLOSE, Map.of(
                                "actor", request.actor(), "reason", request.reason(),
                                "source-profile", closing.profileKey(), "destination-profile", destinationKey,
                                "member-role", member.role())));
                if (swept.status() != TransactionResult.Status.SUCCESS
                        && swept.status() != TransactionResult.Status.DUPLICATE) {
                    return outcome(swept.status() == TransactionResult.Status.UNAVAILABLE
                            ? ManagedAccountMutationResult.Status.UNAVAILABLE
                            : ManagedAccountMutationResult.Status.REJECTED, closing, swept.message());
                }
            }
            memberIndex++;
        }
        repository.completeClose(request.idempotencyKey(), closing.profileKey());
        ManagedAccount closed = repository.find(closing.profileKey(), currencies).orElse(closing);
        return outcome(begun.status() == AccountRegistryRepository.WriteStatus.DUPLICATE
                ? ManagedAccountMutationResult.Status.DUPLICATE
                : ManagedAccountMutationResult.Status.SUCCESS, closed, "closed");
    }

    private static AccountId member(ManagedAccount profile, String role) {
        return profile.members().stream().filter(value -> value.role().equals(role))
                .map(ManagedAccountMember::account).findFirst().orElse(null);
    }

    private static ManagedAccountMutationResult result(AccountRegistryRepository.WriteResult write,
                                                       ManagedAccount account) {
        return outcome(switch (write.status()) {
            case SUCCESS -> ManagedAccountMutationResult.Status.SUCCESS;
            case DUPLICATE -> ManagedAccountMutationResult.Status.DUPLICATE;
            case NOT_FOUND -> ManagedAccountMutationResult.Status.NOT_FOUND;
            case CONFLICT -> ManagedAccountMutationResult.Status.CONFLICT;
            case REJECTED -> ManagedAccountMutationResult.Status.REJECTED;
        }, account, write.message());
    }

    private static ManagedAccountMutationResult outcome(ManagedAccountMutationResult.Status status,
                                                         ManagedAccount account, String message) {
        return new ManagedAccountMutationResult(status, account, message);
    }

    private ManagedAccountMutationResult unavailable(Throwable failure) {
        Throwable root = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        return outcome(ManagedAccountMutationResult.Status.UNAVAILABLE, null,
                root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage());
    }

    private <T> CompletableFuture<T> supply(CheckedSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try { return supplier.get(); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, executor);
    }

    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }

    private static String hashRegistration(ManagedAccountRegistration request) {
        StringBuilder value = new StringBuilder("REGISTER");
        value.append('\u0000').append(request.profileKey()).append('\u0000').append(request.profileType())
                .append('\u0000').append(request.displayName()).append('\u0000').append(request.purpose())
                .append('\u0000').append(request.ownerKind()).append('\u0000').append(request.ownerId())
                .append('\u0000').append(request.founderUuid()).append('\u0000').append(request.sourcePlugin())
                .append('\u0000').append(request.linkedObjectType()).append('\u0000').append(request.linkedObjectId())
                .append('\u0000').append(request.closeDestinationProfile()).append('\u0000').append(request.technical())
                .append('\u0000').append(request.actor()).append('\u0000').append(request.reason());
        request.members().stream().sorted(Comparator.comparing(ManagedAccountMember::role)).forEach(member ->
                value.append('\u0000').append(member.role()).append('=').append(member.account().stableKey())
                        .append('@').append(member.displayOrder()));
        return hash(value.toString());
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
