package ovh.aurumgg.core.engine;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ovh.aurumgg.core.api.CurrencySpec;

public interface PolicyRepository {
    List<FinancialRule> list(CurrencySpec currency) throws SQLException;
    Optional<FinancialRule> find(String id, CurrencySpec currency) throws SQLException;
    long save(FinancialRule rule, CurrencySpec currency, String actor, String reason) throws SQLException;
    /** Atomic compare-and-save; expectedRevision=0 creates only when absent. */
    default long saveIfRevision(FinancialRule rule, CurrencySpec currency, long expectedRevision,
                                String actor, String reason) throws SQLException {
        throw new UnsupportedOperationException("Optimistic policy writes are not implemented");
    }
    Map<String, Long> saveAll(List<FinancialRule> rules, CurrencySpec currency,
                              String actor, String reason) throws SQLException;
    List<PolicyRevision> history(String id, int limit, CurrencySpec currency) throws SQLException;
}
