package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;

public interface LedgerRepository extends AutoCloseable {
    void initialize(CurrencySpec currency) throws SQLException;

    Optional<BigDecimal> balance(AccountId account, CurrencySpec currency) throws SQLException;

    GlobalEconomySnapshot globalSnapshot(CurrencySpec currency) throws SQLException;

    LedgerCommit commit(TransactionPlan plan, CurrencySpec currency) throws SQLException;

    @Override default void close() throws Exception {}
}
