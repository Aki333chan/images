package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.Objects;
import ovh.aurumgg.core.api.AccountId;

/** Account value that must still hold inside the ledger commit transaction. */
public record BalanceExpectation(AccountId account, BigDecimal balance) {
    public BalanceExpectation {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(balance, "balance");
    }
}
