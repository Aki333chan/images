package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.*;

class StartingBalanceServiceTest {
    private final MemoryStartingBalanceRepository repository = new MemoryStartingBalanceRepository();
    private final Map<String, TransactionRequest> committed = new ConcurrentHashMap<>();
    private volatile boolean unavailable;
    private final AurumEconomyApi economy = (AurumEconomyApi) Proxy.newProxyInstance(
            AurumEconomyApi.class.getClassLoader(), new Class<?>[]{AurumEconomyApi.class}, (proxy, method, args) -> {
                if (!method.getName().equals("transfer")) throw new UnsupportedOperationException(method.getName());
                TransactionRequest request = (TransactionRequest) args[0];
                var previous = unavailable ? null : committed.putIfAbsent(request.idempotencyKey(), request);
                if (previous != null) assertEquals(previous, request, "retry must freeze complete intent");
                return CompletableFuture.completedFuture(new TransactionResult(unavailable ? TransactionResult.Status.UNAVAILABLE
                        : previous == null ? TransactionResult.Status.SUCCESS : TransactionResult.Status.DUPLICATE,
                        request.idempotencyKey(), request.amount(), request.amount(), BigDecimal.ZERO, ""));
            });
    private StartingBalanceService service() throws Exception { return new StartingBalanceService(repository, economy, Runnable::run); }

    @Test void oldPlayersNeverReceiveBackfillAndFirstJoinDoesNotPayBeforeAuthentication() throws Exception {
        var service = service();
        assertFalse(service.observe(UUID.randomUUID(), true, 999).toCompletableFuture().join().pending());
        assertFalse(service.observe(UUID.randomUUID(), true, 0).toCompletableFuture().join().pending());
        assertTrue(service.observe(UUID.randomUUID(), false, 0).toCompletableFuture().join().pending());
        assertEquals(0, committed.size(), "observation before login must not move money");
    }
    @Test void disabledOrZeroDecisionsStaySkippedWhenLaterEnabled() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            UUID id = UUID.randomUUID();
            repository.settings = new StartingBalanceSettings(1, enabled, "coins", enabled ? BigDecimal.ZERO : BigDecimal.TEN);
            assertFalse(service().observe(id, false, 2000).toCompletableFuture().join().pending());
            repository.settings = new StartingBalanceSettings(2, true, "coins", BigDecimal.TEN);
            assertFalse(service().observe(id, false, 2000).toCompletableFuture().join().pending());
        }
    }
    @Test void reconnectRestartAndChangedCurrencyCannotGrantTwice() throws Exception {
        UUID id = UUID.randomUUID();
        var decision = service().observe(id, false, 2000).toCompletableFuture().join();
        service().grant(decision).toCompletableFuture().join();
        repository.settings = new StartingBalanceSettings(2, true, "tokens", BigDecimal.TEN);
        assertFalse(service().observe(id, true, 2000).toCompletableFuture().join().pending());
        assertEquals(TransactionResult.Status.DUPLICATE, service().grant(decision).toCompletableFuture().join().status());
        assertEquals(1, committed.size());
        var transfer = committed.values().iterator().next();
        assertEquals(AccountType.SYSTEM_SOURCE, transfer.from().type());
        assertEquals(AccountId.player(id), transfer.to());
        assertEquals(TransactionCategory.STARTING_BALANCE, transfer.category());
        assertEquals("coins", transfer.currencyId());
    }
    @Test void lostCompletionRetriesOriginalIntentAfterConfigChanges() throws Exception {
        UUID id = UUID.randomUUID();
        var decision = service().observe(id, false, 2000).toCompletableFuture().join();
        repository.failFinish = true;
        assertThrows(CompletionException.class, () -> service().grant(decision).toCompletableFuture().join());
        repository.settings = new StartingBalanceSettings(2, false, "tokens", BigDecimal.TEN);
        var recovered = service().observe(id, true, 2000).toCompletableFuture().join();
        assertTrue(recovered.pending());
        assertEquals("coins", recovered.currency());
        assertEquals(TransactionResult.Status.DUPLICATE, service().grant(recovered).toCompletableFuture().join().status());
        assertEquals(1, committed.size());
        assertFalse(service().observe(id, true, 2000).toCompletableFuture().join().pending());
    }
    @Test void unavailableLedgerDoesNotDiscardPromise() throws Exception {
        var decision = service().observe(UUID.randomUUID(), false, 2000).toCompletableFuture().join();
        unavailable = true;
        assertEquals(TransactionResult.Status.UNAVAILABLE, service().grant(decision).toCompletableFuture().join().status());
        assertTrue(repository.players.get(decision.player()).pending());
        unavailable = false;
        assertEquals(TransactionResult.Status.SUCCESS, service().grant(decision).toCompletableFuture().join().status());
    }
    @Test void savedFirstPlayedRecoversJoinWhenObservationWasInterrupted() throws Exception {
        assertTrue(service().observe(UUID.randomUUID(), true, 2000).toCompletableFuture().join().pending());
    }
    @Test void validationRejectsUnknownNegativeExcessPrecisionAndOversize() {
        var currencies = Map.of("coins", new CurrencySpec("coins", "Coins", "$", 2));
        for (String amount : List.of("-1", "0.001", "1000000001"))
            assertThrows(IllegalArgumentException.class, () ->
                    new StartingBalanceSettings(1, true, "coins", new BigDecimal(amount)).validate(currencies));
        assertThrows(IllegalArgumentException.class, () ->
                new StartingBalanceSettings(1, true, "unknown", BigDecimal.TEN).validate(currencies));
    }
    @Test void simultaneousClaimsUseOneUuidKey() throws Exception {
        var service = service();
        var decision = service.observe(UUID.randomUUID(), false, 2000).toCompletableFuture().join();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) jobs.add(executor.submit(() -> service.grant(decision).toCompletableFuture().join()));
            for (var job : jobs) job.get();
        }
        assertEquals(1, committed.size());
    }
}
