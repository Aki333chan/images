package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.Objects;
import ovh.aurumgg.core.api.AccountId;

public record LedgerPosting(AccountId account, BigDecimal amount) {
    public LedgerPosting {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() == 0) throw new IllegalArgumentException("Zero posting is not useful");
    }
}
