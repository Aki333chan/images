package ovh.aurumgg.core.engine.db;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.ExchangeQuote;
import ovh.aurumgg.core.engine.CurrencyAccountKey;
import ovh.aurumgg.core.engine.ExchangeCommit;
import ovh.aurumgg.core.engine.ExchangePlan;
import ovh.aurumgg.core.engine.ExchangeRepository;
import ovh.aurumgg.core.engine.ExchangeRevision;
import ovh.aurumgg.core.engine.ExchangeRule;
import ovh.aurumgg.core.engine.ExchangeSettlement;
import ovh.aurumgg.core.engine.StaleRuleRevisionException;

public final class MariaDbExchangeRepository implements ExchangeRepository {
    private static final Gson JSON = new Gson();
    private static final Type STRING_MAP = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private final DataSource dataSource;

    public MariaDbExchangeRepository(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public List<ExchangeRule> listRules(Map<String, CurrencySpec> currencies) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM aurum_exchange_rules ORDER BY priority DESC, id
                     """); ResultSet result = statement.executeQuery()) {
            List<ExchangeRule> rules = new ArrayList<>();
            while (result.next()) rules.add(readRule(result, currencies));
            return List.copyOf(rules);
        } catch (RuntimeException exception) {
            throw new SQLException("Invalid exchange rule data", exception);
        }
    }

    @Override
    public Optional<ExchangeRule> findRule(String id, Map<String, CurrencySpec> currencies) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM aurum_exchange_rules WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRule(result, currencies)) : Optional.empty();
            }
        }
    }

    @Override
    public List<ExchangeRevision> history(String id, int limit, Map<String, CurrencySpec> currencies)
            throws SQLException {
        int bounded = Math.max(1, Math.min(100, limit));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM aurum_exchange_rule_revisions WHERE rule_id = ?
                     ORDER BY revision DESC LIMIT ?
                     """)) {
            statement.setString(1, id);
            statement.setInt(2, bounded);
            try (ResultSet result = statement.executeQuery()) {
                List<ExchangeRevision> values = new ArrayList<>();
                while (result.next()) values.add(new ExchangeRevision(readRevision(result, currencies),
                        result.getString("changed_by"), result.getString("change_reason"),
                        result.getTimestamp("created_at").toInstant()));
                return List.copyOf(values);
            }
        } catch (RuntimeException exception) {
            throw new SQLException("Invalid exchange revision data", exception);
        }
    }

    @Override
    public long saveRule(ExchangeRule input, Map<String, CurrencySpec> currencies,
                         String actor, String reason) throws SQLException {
        validateText(actor, 128, "actor");
        validateText(reason, 255, "reason");
        validateRule(input, currencies);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long revision = lockRevision(connection, input.id()) + 1;
                ExchangeRule rule = withRevision(input, revision);
                upsertRule(connection, rule, actor, reason);
                insertRevision(connection, rule, actor, reason);
                connection.commit();
                return revision;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    @Override
    public long saveRuleIfRevision(ExchangeRule input, Map<String, CurrencySpec> currencies,
                                   long expectedRevision, String actor, String reason) throws SQLException {
        if (expectedRevision < 0) throw new IllegalArgumentException("Expected revision cannot be negative");
        validateText(actor, 128, "actor");
        validateText(reason, 255, "reason");
        validateRule(input, currencies);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long current = lockRevision(connection, input.id());
                if (current != expectedRevision) {
                    throw new StaleRuleRevisionException(expectedRevision, current);
                }
                long revision = current + 1;
                ExchangeRule rule = withRevision(input, revision);
                if (current == 0) insertRule(connection, rule, actor, reason);
                else upsertRule(connection, rule, actor, reason);
                insertRevision(connection, rule, actor, reason);
                connection.commit();
                return revision;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                if (expectedRevision == 0 && exception instanceof SQLException sql
                        && "23000".equals(sql.getSQLState())) {
                    throw new StaleRuleRevisionException(0, 1);
                }
                throw exception;
            }
        }
    }

    @Override
    public ExchangeCommit execute(ExchangePlan plan, CurrencySpec from, CurrencySpec to) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            UUID exchangeId = UUID.randomUUID();
            try {
                if (!insertPendingExchange(connection, exchangeId, plan)) {
                    connection.rollback();
                    return findByIdempotency(plan.request().idempotencyKey(), from, to)
                            .orElseThrow(() -> new SQLException("Duplicate exchange disappeared"));
                }
                List<Map.Entry<CurrencyAccountKey, BigDecimal>> ordered = plan.postings().entrySet().stream()
                        .filter(entry -> entry.getValue().signum() != 0)
                        .sorted(Comparator.comparing(entry -> entry.getKey().currencyId()
                                + ":" + entry.getKey().account().stableKey())).toList();
                for (var entry : ordered) ensureAccount(connection, entry.getKey(), currencies(from, to));
                Map<CurrencyAccountKey, LockedAccount> locked = new LinkedHashMap<>();
                for (var entry : ordered) {
                    locked.put(entry.getKey(), lockAccount(connection, entry.getKey(), currencies(from, to)));
                }
                Map<CurrencyAccountKey, BigDecimal> after = new LinkedHashMap<>();
                for (var entry : ordered) {
                    CurrencySpec currency = currency(entry.getKey().currencyId(), from, to);
                    BigDecimal balance = locked.get(entry.getKey()).balance().add(entry.getValue())
                            .setScale(currency.scale());
                    if (balance.signum() < 0 && entry.getKey().account().type() != AccountType.SYSTEM_SOURCE) {
                        String reason = "Insufficient exchange liquidity or player balance";
                        rejectExchange(connection, exchangeId, reason);
                        connection.commit();
                        return new ExchangeCommit(ExchangeCommit.Status.REJECTED, plan.quote(), Map.of(), reason);
                    }
                    after.put(entry.getKey(), balance);
                }

                UUID sourceTransaction = insertExchangeTransaction(connection, exchangeId, plan, from, true);
                UUID targetTransaction = insertExchangeTransaction(connection, exchangeId, plan, to, false);
                for (var entry : ordered) {
                    LockedAccount account = locked.get(entry.getKey());
                    BigDecimal balance = after.get(entry.getKey());
                    updateBalance(connection, account.id(), balance);
                    UUID transaction = entry.getKey().currencyId().equals(from.id())
                            ? sourceTransaction : targetTransaction;
                    insertLedgerEntry(connection, transaction, account.id(), entry.getValue(), balance);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE aurum_exchanges SET status = 'COMMITTED', committed_at = CURRENT_TIMESTAMP(6)
                        WHERE id = ?
                        """)) {
                    statement.setString(1, exchangeId.toString());
                    statement.executeUpdate();
                }
                insertOutbox(connection, exchangeId, plan);
                connection.commit();
                return new ExchangeCommit(ExchangeCommit.Status.COMMITTED, plan.quote(), after, "Committed");
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static ExchangeRule readRule(ResultSet result, Map<String, CurrencySpec> currencies)
            throws SQLException {
        Map<String, String> conditions = JSON.fromJson(result.getString("conditions_json"), STRING_MAP);
        ExchangeRule rule = new ExchangeRule(result.getString("id"), result.getLong("revision"),
                result.getString("from_currency_id"), result.getString("to_currency_id"),
                result.getBigDecimal("rate"), result.getBigDecimal("fee_rate"),
                result.getBigDecimal("minimum_source"), result.getBigDecimal("maximum_source"),
                ExchangeSettlement.valueOf(result.getString("settlement")), conditions,
                result.getInt("priority"), result.getBoolean("enabled"),
                instant(result, "effective_from"), instant(result, "effective_until"));
        validateRule(rule, currencies);
        return rule;
    }

    private static ExchangeRule readRevision(ResultSet result, Map<String, CurrencySpec> currencies)
            throws SQLException {
        Map<String, String> conditions = JSON.fromJson(result.getString("conditions_json"), STRING_MAP);
        ExchangeRule rule = new ExchangeRule(result.getString("rule_id"), result.getLong("revision"),
                result.getString("from_currency_id"), result.getString("to_currency_id"),
                result.getBigDecimal("rate"), result.getBigDecimal("fee_rate"),
                result.getBigDecimal("minimum_source"), result.getBigDecimal("maximum_source"),
                ExchangeSettlement.valueOf(result.getString("settlement")), conditions,
                result.getInt("priority"), result.getBoolean("enabled"),
                instant(result, "effective_from"), instant(result, "effective_until"));
        validateRule(rule, currencies);
        return rule;
    }

    private static void validateRule(ExchangeRule rule, Map<String, CurrencySpec> currencies) {
        CurrencySpec from = currencies.get(rule.fromCurrencyId());
        CurrencySpec to = currencies.get(rule.toCurrencyId());
        if (from == null || to == null) throw new IllegalArgumentException("Exchange uses an unknown currency");
        if (rule.minimumSource() != null) from.requireAmount(rule.minimumSource());
        if (rule.maximumSource() != null) from.requireAmount(rule.maximumSource());
        String type = rule.conditions().get("account-type");
        if (type != null) AccountType.valueOf(type.toUpperCase(Locale.ROOT));
        if ((rule.conditions().containsKey("metadata-key"))
                != rule.conditions().containsKey("metadata-value")) {
            throw new IllegalArgumentException("Incomplete exchange metadata condition");
        }
    }

    private static long lockRevision(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision FROM aurum_exchange_rules WHERE id = ? FOR UPDATE")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0L; }
        }
    }

    private static ExchangeRule withRevision(ExchangeRule rule, long revision) {
        return new ExchangeRule(rule.id(), revision, rule.fromCurrencyId(), rule.toCurrencyId(),
                rule.rate(), rule.feeRate(), rule.minimumSource(), rule.maximumSource(), rule.settlement(),
                rule.conditions(), rule.priority(), rule.enabled(), rule.effectiveFrom(), rule.effectiveUntil());
    }

    private static void upsertRule(Connection connection, ExchangeRule rule, String actor, String reason)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_exchange_rules(id, from_currency_id, to_currency_id, rate, fee_rate,
                    minimum_source, maximum_source, settlement, conditions_json, priority, enabled,
                    effective_from, effective_until, revision, updated_by, update_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE from_currency_id=VALUES(from_currency_id),
                    to_currency_id=VALUES(to_currency_id), rate=VALUES(rate), fee_rate=VALUES(fee_rate),
                    minimum_source=VALUES(minimum_source), maximum_source=VALUES(maximum_source),
                    settlement=VALUES(settlement), conditions_json=VALUES(conditions_json),
                    priority=VALUES(priority), enabled=VALUES(enabled), effective_from=VALUES(effective_from),
                    effective_until=VALUES(effective_until), revision=VALUES(revision),
                    updated_by=VALUES(updated_by), update_reason=VALUES(update_reason)
                """)) {
            bindRule(statement, rule, 1, true);
            statement.setString(15, actor);
            statement.setString(16, reason);
            statement.executeUpdate();
        }
    }

    /** Create-only path used by the optimistic editor; never overwrites a racing create. */
    private static void insertRule(Connection connection, ExchangeRule rule, String actor, String reason)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_exchange_rules(id, from_currency_id, to_currency_id, rate, fee_rate,
                    minimum_source, maximum_source, settlement, conditions_json, priority, enabled,
                    effective_from, effective_until, revision, updated_by, update_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindRule(statement, rule, 1, true);
            statement.setString(15, actor);
            statement.setString(16, reason);
            statement.executeUpdate();
        }
    }

    private static void insertRevision(Connection connection, ExchangeRule rule, String actor, String reason)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_exchange_rule_revisions(rule_id, revision, from_currency_id,
                    to_currency_id, rate, fee_rate, minimum_source, maximum_source, settlement,
                    conditions_json, priority, enabled, effective_from, effective_until,
                    changed_by, change_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, rule.id());
            statement.setLong(2, rule.revision());
            bindRule(statement, rule, 3, false);
            statement.setString(15, actor);
            statement.setString(16, reason);
            statement.executeUpdate();
        }
    }

    private static void bindRule(PreparedStatement statement, ExchangeRule rule, int first,
                                 boolean includeIdAndRevision) throws SQLException {
        int index = first;
        if (includeIdAndRevision) statement.setString(index++, rule.id());
        statement.setString(index++, rule.fromCurrencyId());
        statement.setString(index++, rule.toCurrencyId());
        statement.setBigDecimal(index++, rule.rate());
        statement.setBigDecimal(index++, rule.feeRate());
        statement.setBigDecimal(index++, rule.minimumSource());
        statement.setBigDecimal(index++, rule.maximumSource());
        statement.setString(index++, rule.settlement().name());
        statement.setString(index++, JSON.toJson(rule.conditions()));
        statement.setInt(index++, rule.priority());
        statement.setBoolean(index++, rule.enabled());
        setInstant(statement, index++, rule.effectiveFrom());
        setInstant(statement, index++, rule.effectiveUntil());
        if (includeIdAndRevision) statement.setLong(index, rule.revision());
    }

    private static boolean insertPendingExchange(Connection connection, UUID id, ExchangePlan plan)
            throws SQLException {
        ExchangeQuote quote = plan.quote();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_exchanges(id, idempotency_key, rule_id, rule_revision,
                    account_type, account_reference, from_currency_id, to_currency_id,
                    source_amount, fee_amount, converted_amount, target_amount, settlement,
                    status, metadata_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, plan.request().idempotencyKey());
            statement.setString(3, quote.ruleId());
            statement.setLong(4, quote.ruleRevision());
            statement.setString(5, quote.account().type().name());
            statement.setString(6, quote.account().reference());
            statement.setString(7, quote.fromCurrency().id());
            statement.setString(8, quote.toCurrency().id());
            statement.setBigDecimal(9, quote.sourceAmount());
            statement.setBigDecimal(10, quote.feeAmount());
            statement.setBigDecimal(11, quote.convertedAmount());
            statement.setBigDecimal(12, quote.targetAmount());
            statement.setString(13, plan.settlement().name());
            statement.setString(14, JSON.toJson(plan.request().metadata()));
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            if ("23000".equals(exception.getSQLState())) return false;
            throw exception;
        }
    }

    @Override
    public Optional<ExchangeCommit> findByIdempotency(String key, CurrencySpec from, CurrencySpec to)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM aurum_exchanges WHERE idempotency_key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                AccountId account = new AccountId(AccountType.valueOf(result.getString("account_type")),
                        result.getString("account_reference"));
                Instant created = result.getTimestamp("created_at").toInstant();
                ExchangeQuote quote = new ExchangeQuote(result.getString("rule_id"),
                        result.getLong("rule_revision"), account, from, to,
                        result.getBigDecimal("source_amount"), result.getBigDecimal("fee_amount"),
                        result.getBigDecimal("converted_amount"), result.getBigDecimal("target_amount"),
                        created, created.plusSeconds(1));
                boolean committed = "COMMITTED".equals(result.getString("status"));
                Map<CurrencyAccountKey, BigDecimal> balances = committed
                        ? playerBalances(connection, account, from, to) : Map.of();
                return Optional.of(new ExchangeCommit(committed ? ExchangeCommit.Status.DUPLICATE
                        : ExchangeCommit.Status.REJECTED, quote, balances,
                        committed ? "Existing exchange already committed" : result.getString("failure_reason")));
            }
        }
    }

    private static Map<CurrencyAccountKey, BigDecimal> playerBalances(Connection connection, AccountId account,
                                                                       CurrencySpec from, CurrencySpec to)
            throws SQLException {
        Map<CurrencyAccountKey, BigDecimal> values = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT currency_id, balance FROM aurum_accounts
                WHERE account_type=? AND reference_id=? AND currency_id IN (?, ?)
                """)) {
            statement.setString(1, account.type().name());
            statement.setString(2, account.reference());
            statement.setString(3, from.id());
            statement.setString(4, to.id());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.put(new CurrencyAccountKey(result.getString("currency_id"), account),
                        result.getBigDecimal("balance").setScale(currency(result.getString("currency_id"), from, to).scale()));
            }
        }
        return Map.copyOf(values);
    }

    private static Map<String, CurrencySpec> currencies(CurrencySpec from, CurrencySpec to) {
        return Map.of(from.id(), from, to.id(), to);
    }

    private static CurrencySpec currency(String id, CurrencySpec from, CurrencySpec to) {
        if (id.equals(from.id())) return from;
        if (id.equals(to.id())) return to;
        throw new IllegalArgumentException("Unknown exchange currency: " + id);
    }

    private static void ensureAccount(Connection connection, CurrencyAccountKey key,
                                      Map<String, CurrencySpec> currencies) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_accounts(account_type, reference_id, currency_id, balance)
                VALUES (?, ?, ?, 0) ON DUPLICATE KEY UPDATE id=id
                """)) {
            statement.setString(1, key.account().type().name());
            statement.setString(2, key.account().reference());
            statement.setString(3, currencies.get(key.currencyId()).id());
            statement.executeUpdate();
        }
    }

    private static LockedAccount lockAccount(Connection connection, CurrencyAccountKey key,
                                             Map<String, CurrencySpec> currencies) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, balance FROM aurum_accounts WHERE account_type=? AND reference_id=?
                    AND currency_id=? FOR UPDATE
                """)) {
            statement.setString(1, key.account().type().name());
            statement.setString(2, key.account().reference());
            statement.setString(3, currencies.get(key.currencyId()).id());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Exchange account missing after creation");
                return new LockedAccount(result.getLong("id"), result.getBigDecimal("balance"));
            }
        }
    }

    private static UUID insertExchangeTransaction(Connection connection, UUID exchangeId,
                                                   ExchangePlan plan, CurrencySpec currency,
                                                   boolean source) throws SQLException {
        UUID id = UUID.randomUUID();
        BigDecimal gross = source ? plan.quote().sourceAmount() : plan.quote().targetAmount();
        BigDecimal net = source ? plan.quote().convertedAmount() : plan.quote().targetAmount();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_transactions(id, idempotency_key, currency_id, category, status,
                    gross_amount, net_amount, tax_amount, metadata_json, committed_at)
                VALUES (?, ?, ?, 'CURRENCY_EXCHANGE', 'COMMITTED', ?, ?, 0, ?, CURRENT_TIMESTAMP(6))
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, "exchange:" + exchangeId + (source ? ":source" : ":target"));
            statement.setString(3, currency.id());
            statement.setBigDecimal(4, gross);
            statement.setBigDecimal(5, net);
            statement.setString(6, JSON.toJson(Map.of("exchangeId", exchangeId.toString(),
                    "ruleId", plan.quote().ruleId(), "ruleRevision", Long.toString(plan.quote().ruleRevision()))));
            statement.executeUpdate();
        }
        return id;
    }

    private static void updateBalance(Connection connection, long id, BigDecimal balance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE aurum_accounts SET balance=?, version=version+1 WHERE id=?")) {
            statement.setBigDecimal(1, balance);
            statement.setLong(2, id);
            if (statement.executeUpdate() != 1) throw new SQLException("Exchange balance update failed");
        }
    }

    private static void insertLedgerEntry(Connection connection, UUID transaction, long account,
                                          BigDecimal amount, BigDecimal after) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_ledger_entries(transaction_id, account_id, amount, balance_after)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, transaction.toString());
            statement.setLong(2, account);
            statement.setBigDecimal(3, amount);
            statement.setBigDecimal(4, after);
            statement.executeUpdate();
        }
    }

    private static void rejectExchange(Connection connection, UUID id, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE aurum_exchanges SET status='REJECTED', failure_reason=?,
                    committed_at=CURRENT_TIMESTAMP(6) WHERE id=?
                """)) {
            statement.setString(1, reason);
            statement.setString(2, id.toString());
            statement.executeUpdate();
        }
    }

    private static void insertOutbox(Connection connection, UUID exchangeId, ExchangePlan plan)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_outbox(event_id, event_type, payload_json)
                VALUES (?, 'economy.exchange', ?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, JSON.toJson(Map.of("exchangeId", exchangeId.toString(),
                    "idempotencyKey", plan.request().idempotencyKey())));
            statement.executeUpdate();
        }
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private static void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setTimestamp(index, value == null ? null : Timestamp.from(value));
    }
    private static void validateText(String value, int limit, String label) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); } catch (SQLException rollback) { original.addSuppressed(rollback); }
    }
    private record LockedAccount(long id, BigDecimal balance) {}
}
