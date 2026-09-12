package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import ovh.aurumgg.core.api.AccountId;

public record LedgerPostingAudit(AccountId account, BigDecimal amount, BigDecimal balanceAfter) {}
