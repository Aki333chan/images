package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;

public interface LedgerRepository extends AutoCloseable {
    void initialize(CurrencySpec currency) throws SQLException;

    Optional<BigDecimal> balance(AccountId account, CurrencySpec currency) throws SQLException;

    GlobalEconomySnapshot globalSnapshot(CurrencySpec currency) throws SQLException;

    LedgerCommit commit(TransactionPlan plan, CurrencySpec currency) throws SQLException;

    default LedgerCommit commit(TransactionPlan plan, CurrencySpec currency, UUID excludedHold)
            throws SQLException {
        return commit(plan, currency);
    }

    /** True only when the idempotent transaction reached its durable COMMITTED state. */
    default boolean transactionCommitted(String idempotencyKey) throws SQLException {
        return false;
    }

    @Override default void close() throws Exception {}
}
