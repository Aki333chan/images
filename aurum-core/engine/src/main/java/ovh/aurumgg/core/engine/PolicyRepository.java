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
    Map<String, Long> saveAll(List<FinancialRule> rules, CurrencySpec currency,
                              String actor, String reason) throws SQLException;
    List<PolicyRevision> history(String id, int limit, CurrencySpec currency) throws SQLException;
}
