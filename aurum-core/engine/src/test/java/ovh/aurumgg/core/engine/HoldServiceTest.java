package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;
import ovh.aurumgg.core.api.HoldRequest;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.HoldSnapshot;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;

class HoldServiceTest {
    private static final CurrencySpec COINS = new CurrencySpec("coins", "Coins", "$", 2);
    private static final AccountId PLAYER = AccountId.player(
            UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final AccountId SHOP = new AccountId(AccountType.NPC_SHOP, "food");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void captureIsIdempotentAndChargesOnlyOnce() {
        MemoryLedger ledger = new MemoryLedger(); ledger.balances.put(PLAYER, amount("100"));
        MemoryHolds holds = new MemoryHolds();
        MultiCurrencyEconomyService economy = economy(ledger);
        HoldService service = new HoldService(Map.of("coins", COINS), holds, economy, Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC), new Object(), java.time.Duration.ofMinutes(5));
        HoldRequest request = request("npc:shop:one", "10");
        HoldResult created = service.create(request).toCompletableFuture().join();
        TransactionRequest capture = request.transaction("ignored", request.metadata());

        assertEquals(HoldResult.Status.SUCCESS, created.status());
        UUID id = created.hold().orElseThrow().id();
        assertEquals(HoldResult.Status.SUCCESS, service.capture(id, capture).toCompletableFuture().join().status());
        assertEquals(HoldResult.Status.DUPLICATE, service.capture(id, capture).toCompletableFuture().join().status());
        assertEquals(amount("90"), ledger.value(PLAYER));
        assertEquals(amount("10"), ledger.value(SHOP));
    }

    @Test
    void changedIntentCannotCaptureReservedFunds() {
        MemoryLedger ledger = new MemoryLedger(); ledger.balances.put(PLAYER, amount("100"));
        MemoryHolds holds = new MemoryHolds();
        MultiCurrencyEconomyService economy = economy(ledger);
        HoldService service = new HoldService(Map.of("coins", COINS), holds, economy, Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC), new Object(), java.time.Duration.ofMinutes(5));
        HoldResult created = service.create(request("npc:shop:two", "10")).toCompletableFuture().join();
        TransactionRequest changed = new TransactionRequest("ignored", PLAYER, SHOP, "coins", amount("11"),
                TransactionCategory.NPC_PURCHASE, Map.of("plugin", "AddonsNPC"));

        assertEquals(HoldResult.Status.REJECTED, service.capture(created.hold().orElseThrow().id(), changed)
                .toCompletableFuture().join().status());
        assertEquals(amount("100"), ledger.value(PLAYER));
    }

    @Test
    void captureRetryFinishesHoldAfterTransactionWasCommitted() {
        MemoryLedger ledger = new MemoryLedger(); ledger.balances.put(PLAYER, amount("100"));
        MemoryHolds holds = new MemoryHolds(); holds.failNextCaptureResolution = true;
        MultiCurrencyEconomyService economy = economy(ledger);
        HoldService service = new HoldService(Map.of("coins", COINS), holds, economy, Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC), new Object(), java.time.Duration.ofMinutes(5));
        HoldRequest request = request("npc:shop:recovery", "10");
        UUID id = service.create(request).toCompletableFuture().join().hold().orElseThrow().id();
        TransactionRequest capture = request.transaction("ignored", request.metadata());

        assertEquals(HoldResult.Status.UNAVAILABLE, service.capture(id, capture).toCompletableFuture().join().status());
        assertEquals(HoldResult.Status.SUCCESS, service.capture(id, capture).toCompletableFuture().join().status());
        assertEquals(amount("90"), ledger.value(PLAYER));
        assertEquals(amount("10"), ledger.value(SHOP));
    }

    private static HoldRequest request(String key, String amount) {
        return new HoldRequest(key, PLAYER, SHOP, "coins", amount(amount), TransactionCategory.NPC_PURCHASE,
                "npc-shop", "food:11", NOW.plusSeconds(30), Map.of("plugin", "AddonsNPC"));
    }
    private static MultiCurrencyEconomyService economy(MemoryLedger ledger) {
        LedgerEconomyService service = new LedgerEconomyService(COINS, ledger, FinancialRuleResolver.none(),
                Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC), new Object());
        service.seedBalances(ledger.balances);
        return new MultiCurrencyEconomyService(COINS, Map.of("coins", service));
    }
    private static BigDecimal amount(String value) { return new BigDecimal(value).setScale(2); }

    private static final class MemoryHolds implements HoldRepository {
        private final Map<UUID, HoldSnapshot> values = new HashMap<>();
        private boolean failNextCaptureResolution;
        @Override public HoldResult reserve(HoldSnapshot hold) {
            values.put(hold.id(), hold); return new HoldResult(HoldResult.Status.SUCCESS, Optional.of(hold), "held");
        }
        @Override public Optional<HoldSnapshot> find(UUID id, CurrencySpec currency) {
            return Optional.ofNullable(values.get(id));
        }
        @Override public Optional<HoldSnapshot> find(String key, CurrencySpec currency) {
            return values.values().stream().filter(value -> value.idempotencyKey().equals(key)).findFirst();
        }
        @Override public HoldResult resolve(UUID id, HoldSnapshot.Status status, CurrencySpec currency) {
            if (status == HoldSnapshot.Status.CAPTURED && failNextCaptureResolution) {
                failNextCaptureResolution = false;
                throw new IllegalStateException("temporary hold write failure");
            }
            HoldSnapshot old = values.get(id);
            if (old.status() == status) return new HoldResult(HoldResult.Status.DUPLICATE, Optional.of(old), "same");
            HoldSnapshot next = new HoldSnapshot(old.id(), old.idempotencyKey(), old.from(), old.to(), old.currency(),
                    old.amount(), old.reservedAmount(), old.category(), old.purpose(), old.referenceId(), status,
                    old.createdAt(), old.expiresAt(), old.metadata());
            values.put(id, next); return new HoldResult(HoldResult.Status.SUCCESS, Optional.of(next), "resolved");
        }
    }

    private static final class MemoryLedger implements LedgerRepository {
        private final Map<AccountId, BigDecimal> balances = new HashMap<>();
        private final Map<String, LedgerCommit> commits = new HashMap<>();
        BigDecimal value(AccountId account) { return balances.getOrDefault(account, amount("0")); }
        @Override public void initialize(CurrencySpec currency) {}
        @Override public Optional<BigDecimal> balance(AccountId account, CurrencySpec currency) {
            return Optional.of(value(account));
        }
        @Override public GlobalEconomySnapshot globalSnapshot(CurrencySpec currency) {
            throw new UnsupportedOperationException();
        }
        @Override public synchronized LedgerCommit commit(TransactionPlan plan, CurrencySpec currency) {
            LedgerCommit duplicate = commits.get(plan.request().idempotencyKey());
            if (duplicate != null) return new LedgerCommit(LedgerCommit.Status.DUPLICATE,
                    duplicate.transactionId(), duplicate.grossAmount(), duplicate.netAmount(), duplicate.taxAmount(),
                    duplicate.sourceBalance(), duplicate.targetBalance(), "duplicate", duplicate.balancesAfter());
            Map<AccountId, BigDecimal> after = new HashMap<>();
            for (LedgerPosting posting : plan.postings()) {
                BigDecimal next = value(posting.account()).add(posting.amount());
                if (next.signum() < 0 && posting.account().type() != AccountType.SYSTEM_SOURCE)
                    return new LedgerCommit(LedgerCommit.Status.INSUFFICIENT_FUNDS, UUID.randomUUID(),
                            plan.request().amount(), BigDecimal.ZERO, BigDecimal.ZERO, value(plan.request().from()),
                            value(plan.request().to()), "insufficient");
                after.put(posting.account(), next);
            }
            balances.putAll(after);
            LedgerCommit result = new LedgerCommit(LedgerCommit.Status.COMMITTED, UUID.randomUUID(),
                    plan.request().amount(), plan.targetCredit(), plan.taxCredit(), value(plan.request().from()),
                    value(plan.request().to()), "committed", after);
            commits.put(plan.request().idempotencyKey(), result); return result;
        }
        @Override public synchronized boolean transactionCommitted(String idempotencyKey) {
            return commits.containsKey(idempotencyKey);
        }
    }
}
