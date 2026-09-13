package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.ManagedAccountStatus;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;

class AccountStatusGateTest {
    private static final CurrencySpec COINS = new CurrencySpec("coins", "Coins", "$", 2);
    private static final AccountId MACHINE = new AccountId(AccountType.SLOTS, "one");
    private static final AccountId TREASURY = AccountId.globalTreasury();

    @Test void frozenAccountRejectsBothIncomingAndOutgoingPayments() {
        AccountStatusGate gate = new AccountStatusGate(Map.of(MACHINE, ManagedAccountStatus.FROZEN));

        assertTrue(gate.rejection(plan(MACHINE, TREASURY, TransactionCategory.SLOT_BET)).contains("FROZEN"));
        assertTrue(gate.rejection(plan(TREASURY, MACHINE, TransactionCategory.SLOT_PAYOUT)).contains("FROZEN"));
    }

    @Test void closingAccountAllowsOnlyItsOwnCloseDebit() {
        AccountStatusGate gate = new AccountStatusGate(Map.of(MACHINE, ManagedAccountStatus.CLOSING));

        assertNull(gate.rejection(plan(MACHINE, TREASURY, TransactionCategory.ACCOUNT_CLOSE)));
        assertTrue(gate.rejection(plan(TREASURY, MACHINE, TransactionCategory.ACCOUNT_CLOSE)).contains("CLOSING"));
        assertTrue(gate.rejection(plan(MACHINE, TREASURY, TransactionCategory.SLOT_PAYOUT)).contains("CLOSING"));
    }

    @Test void unregisteredAndActiveAccountsStayOnTheLockFreeHappyPath() {
        AccountStatusGate gate = new AccountStatusGate(Map.of(MACHINE, ManagedAccountStatus.ACTIVE));
        assertNull(gate.rejection(plan(MACHINE, TREASURY, TransactionCategory.SLOT_BET)));
        assertNull(gate.rejection(plan(new AccountId(AccountType.NPC_BUYER, "future"), TREASURY,
                TransactionCategory.NPC_SALE)));
    }

    private static TransactionPlan plan(AccountId from, AccountId to, TransactionCategory category) {
        TransactionRequest request = new TransactionRequest("test:" + from.stableKey() + ":" + to.stableKey(),
                from, to, COINS.id(), new BigDecimal("10.00"), category, Map.of());
        return TransactionPlanner.plan(request, COINS, List.of(), Instant.EPOCH);
    }
}
