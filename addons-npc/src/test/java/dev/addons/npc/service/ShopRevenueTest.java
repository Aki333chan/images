package dev.addons.npc.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.*;

class ShopRevenueTest {
    private EconomyService economy(ManagedAccount account) throws Exception {
        EconomyService economy = new EconomyService(null);
        AurumAccountRegistryApi registry = (AurumAccountRegistryApi) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{AurumAccountRegistryApi.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("find")) throw new UnsupportedOperationException();
                    return CompletableFuture.completedFuture(
                            account != null && account.profileKey().equals(args[0]) ? Optional.of(account) : Optional.empty());
                });
        var field = EconomyService.class.getDeclaredField("accounts");
        field.setAccessible(true);
        field.set(economy, registry);
        return economy;
    }

    private ManagedAccount arena(ManagedAccountStatus status, boolean technical) {
        return new ManagedAccount("arena:colosseum", "ARENA", "Colosseum", "", "SERVER", "global", "",
                "AurumArena", "ARENA", "colosseum", status, "treasury:global", technical,
                List.of(new ManagedAccountMember(new AccountId(AccountType.ARENA_ESCROW, "bet:colosseum"), "bet", 0),
                        new ManagedAccountMember(new AccountId(AccountType.ARENA_ESCROW, "final:colosseum"), "final", 1)),
                Map.of(), Instant.EPOCH, Instant.EPOCH, null);
    }

    @Test void selectingFinalUsesExactRoleAndNotBettingBalance() throws Exception {
        var economy = economy(arena(ManagedAccountStatus.ACTIVE, false));
        assertEquals(new AccountId(AccountType.ARENA_ESCROW, "final:colosseum"),
                economy.revenueAccount("arena:colosseum", "final").toCompletableFuture().join());
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> economy.revenueAccount("arena:colosseum", "primary").toCompletableFuture().join());
    }

    @Test void unknownFrozenClosedAndTechnicalAccountsFailClosed() throws Exception {
        for (var status : List.of(ManagedAccountStatus.FROZEN, ManagedAccountStatus.CLOSED)) {
            var economy = economy(arena(status, false));
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> economy.revenueAccount("arena:colosseum", "final").toCompletableFuture().join());
        }
        var technical = economy(arena(ManagedAccountStatus.ACTIVE, true));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> technical.revenueAccount("arena:colosseum", "final").toCompletableFuture().join());
        var missing = economy(null);
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> missing.revenueAccount("treasury:missing", "primary").toCompletableFuture().join());
    }

    @Test void technicalEscrowsAndEmissionCannotBeRevenueDestinations() {
        for (var type : List.of(AccountType.SYSTEM_SOURCE, AccountType.SYSTEM_SINK,
                AccountType.TRADE_ESCROW, AccountType.EXCHANGE_RESERVE))
            assertFalse(EconomyService.revenueEligible(new AccountId(type, "test")));
        assertTrue(EconomyService.revenueEligible(AccountId.globalTreasury()));
    }
}
