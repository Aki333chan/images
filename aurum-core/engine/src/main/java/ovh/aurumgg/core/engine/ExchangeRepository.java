package ovh.aurumgg.core.engine;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ovh.aurumgg.core.api.CurrencySpec;

public interface ExchangeRepository {
    List<ExchangeRule> listRules(Map<String, CurrencySpec> currencies) throws SQLException;
    Optional<ExchangeRule> findRule(String id, Map<String, CurrencySpec> currencies) throws SQLException;
    List<ExchangeRevision> history(String id, int limit, Map<String, CurrencySpec> currencies) throws SQLException;
    long saveRule(ExchangeRule rule, Map<String, CurrencySpec> currencies,
                  String actor, String reason) throws SQLException;
    Optional<ExchangeCommit> findByIdempotency(String key, CurrencySpec fromCurrency,
                                               CurrencySpec toCurrency) throws SQLException;
    ExchangeCommit execute(ExchangePlan plan, CurrencySpec fromCurrency,
                           CurrencySpec toCurrency) throws SQLException;
}
