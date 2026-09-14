package org.ChisaO_o.gladiatorArena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.CurrencySpec;

/** Regression coverage for panel-funded champion pools. */
class FinalPoolBalanceTest {

    private static final CurrencySpec COINS = new CurrencySpec("coins", "Coins", "$", 2);

    @Test
    @DisplayName("Голограмма читает роль final той же арены")
    void readsFinalEscrowAccount() throws Exception {
        AtomicReference<AccountId> requested = new AtomicReference<>();
        ArenaEconomyService service = serviceWithBalance(requested, true, new BigDecimal("500.00"));

        Optional<BigDecimal> balance = service.finalPoolBalance("colosseum").toCompletableFuture().join();

        assertEquals(new AccountId(AccountType.ARENA_ESCROW, "final:colosseum"), requested.get());
        assertEquals(0, new BigDecimal("500.00").compareTo(balance.orElseThrow()));
    }

    @Test
    @DisplayName("Неавторитетный провайдер не подменяет локальный резервный снимок")
    void ignoresNonAuthoritativeBalance() throws Exception {
        ArenaEconomyService service = serviceWithBalance(new AtomicReference<>(), false,
                new BigDecimal("500.00"));

        assertTrue(service.finalPoolBalance("colosseum").toCompletableFuture().join().isEmpty());
    }

    private static ArenaEconomyService serviceWithBalance(AtomicReference<AccountId> requested,
                                                           boolean authoritative,
                                                           BigDecimal value) throws Exception {
        AurumEconomyApi api = (AurumEconomyApi) Proxy.newProxyInstance(
                FinalPoolBalanceTest.class.getClassLoader(),
                new Class<?>[] {AurumEconomyApi.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("balance") && arguments != null && arguments.length == 1) {
                        AccountId account = (AccountId) arguments[0];
                        requested.set(account);
                        return CompletableFuture.completedFuture(Optional.of(new BalanceSnapshot(
                                account, COINS, value, Instant.EPOCH, authoritative)));
                    }
                    if (method.getName().equals("primaryCurrency")) return COINS;
                    throw new UnsupportedOperationException(method.getName());
                });
        ArenaEconomyService service = new ArenaEconomyService(null, null, null);
        Field field = ArenaEconomyService.class.getDeclaredField("api");
        field.setAccessible(true);
        field.set(service, api);
        return service;
    }
}
