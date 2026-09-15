package ovh.aurumgg.core.engine;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import ovh.aurumgg.core.api.*;

/** Persistent eligibility + immutable intent + ledger idempotency; no balance-based eligibility. */
public final class StartingBalanceService {
    private final StartingBalanceRepository repository;
    private final AurumEconomyApi economy;
    private final Executor executor;
    private final long installedAt;

    public StartingBalanceService(StartingBalanceRepository repository, AurumEconomyApi economy,
                                  Executor executor) throws Exception {
        this.repository = repository;
        this.economy = economy;
        this.executor = executor;
        this.installedAt = repository.installedAt();
    }

    public CompletionStage<StartingBalanceRepository.Decision> observe(UUID player, boolean playedBefore, long firstPlayed) {
        boolean newcomer = !playedBefore || (firstPlayed > 0 && firstPlayed >= installedAt);
        return CompletableFuture.supplyAsync(() -> {
            try { return repository.observe(player, newcomer); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, executor);
    }

    /** Called only after authentication by Paper. Retry uses the frozen first-join amount/currency. */
    public CompletionStage<TransactionResult> grant(StartingBalanceRepository.Decision decision) {
        if (!decision.pending()) return CompletableFuture.failedFuture(new IllegalArgumentException("Not pending"));
        TransactionRequest request = new TransactionRequest(
                "starting-balance:" + decision.player(),
                new AccountId(AccountType.SYSTEM_SOURCE, "global"), AccountId.player(decision.player()),
                decision.currency(), decision.amount(), TransactionCategory.STARTING_BALANCE,
                Map.of("actor", "system:AurumCore", "reason", "new-player-starting-balance",
                        "settingsRevision", Long.toString(decision.revision())));
        return economy.transfer(request).thenCompose(result -> {
            if (result.status() != TransactionResult.Status.SUCCESS && result.status() != TransactionResult.Status.DUPLICATE)
                return CompletableFuture.completedFuture(result);
            return CompletableFuture.supplyAsync(() -> {
                try { repository.finish(decision.player()); return result; }
                catch (Exception failure) { throw new CompletionException(failure); }
            }, executor);
        });
    }
}
