package ovh.aurumgg.core.engine.db;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.engine.FinancialRule;
import ovh.aurumgg.core.engine.PolicyKind;
import ovh.aurumgg.core.engine.PolicyRepository;
import ovh.aurumgg.core.engine.PolicyRevision;
import ovh.aurumgg.core.engine.PolicyValidator;
import ovh.aurumgg.core.engine.StaleRuleRevisionException;

public final class MariaDbPolicyRepository implements PolicyRepository {
    private static final Gson JSON = new Gson();
    private static final Type STRING_MAP = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private final DataSource dataSource;

    public MariaDbPolicyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<FinancialRule> list(CurrencySpec currency) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, revision, rule_kind, handler_version, categories_json, definition_json,
                         priority, enabled, effective_from, effective_until
                     FROM aurum_financial_rules ORDER BY priority DESC, id
                     """);
             ResultSet result = statement.executeQuery()) {
            var rules = new java.util.ArrayList<FinancialRule>();
            while (result.next()) rules.add(readRule(result, "id", currency));
            return List.copyOf(rules);
        } catch (RuntimeException exception) {
            throw new SQLException("Invalid financial policy data", exception);
        }
    }

    @Override
    public Optional<FinancialRule> find(String id, CurrencySpec currency) throws SQLException {
        validateText(id, 64, "rule id");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, revision, rule_kind, handler_version, categories_json, definition_json,
                         priority, enabled, effective_from, effective_until
                     FROM aurum_financial_rules WHERE id = ?
                     """)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRule(result, "id", currency)) : Optional.empty();
            }
        } catch (RuntimeException exception) {
            throw new SQLException("Invalid financial policy data", exception);
        }
    }

    @Override
    public long save(FinancialRule input, CurrencySpec currency, String actor, String reason) throws SQLException {
        return saveAll(List.of(input), currency, actor, reason).get(input.id());
    }

    @Override
    public long saveIfRevision(FinancialRule input, CurrencySpec currency, long expectedRevision,
                               String actor, String reason) throws SQLException {
        FinancialRule rule = PolicyValidator.validate(input, currency);
        if (expectedRevision < 0) throw new IllegalArgumentException("Expected revision cannot be negative");
        validateText(actor, 128, "actor");
        validateText(reason, 255, "reason");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long current = lockRevision(connection, rule.id());
                if (current != expectedRevision) {
                    throw new StaleRuleRevisionException(expectedRevision, current);
                }
                long revision = current + 1;
                if (current == 0) insertCurrent(connection, rule, revision, actor, reason);
                else upsertCurrent(connection, rule, revision, actor, reason);
                insertRevision(connection, rule, revision, actor, reason);
                connection.commit();
                return revision;
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                // Concurrent create of the same id can surface as a unique-key
                // conflict because there was no existing row to lock.
                if (expectedRevision == 0 && exception instanceof SQLException sql
                        && "23000".equals(sql.getSQLState())) {
                    throw new StaleRuleRevisionException(0, 1);
                }
                throw exception;
            }
        }
    }

    @Override
    public Map<String, Long> saveAll(List<FinancialRule> inputs, CurrencySpec currency,
                                     String actor, String reason) throws SQLException {
        if (inputs.isEmpty()) throw new IllegalArgumentException("At least one policy is required");
        List<FinancialRule> rules = inputs.stream().map(rule -> PolicyValidator.validate(rule, currency))
                .sorted(java.util.Comparator.comparing(FinancialRule::id)).toList();
        if (rules.stream().map(FinancialRule::id).map(id -> id.toLowerCase(Locale.ROOT))
                .distinct().count() != rules.size()) {
            throw new IllegalArgumentException("Duplicate policy id in batch");
        }
        validateText(actor, 128, "actor");
        validateText(reason, 255, "reason");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Long> revisions = new LinkedHashMap<>();
                for (FinancialRule rule : rules) {
                    long revision = lockRevision(connection, rule.id()) + 1;
                    upsertCurrent(connection, rule, revision, actor, reason);
                    insertRevision(connection, rule, revision, actor, reason);
                    revisions.put(rule.id(), revision);
                }
                connection.commit();
                return Map.copyOf(revisions);
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        }
    }

    @Override
    public List<PolicyRevision> history(String id, int limit, CurrencySpec currency) throws SQLException {
        validateText(id, 64, "rule id");
        int safeLimit = Math.max(1, Math.min(100, limit));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT rule_id, revision, rule_kind, handler_version, categories_json,
                         definition_json, priority, enabled, effective_from, effective_until,
                         changed_by, change_reason, created_at
                     FROM aurum_financial_rule_revisions WHERE rule_id = ?
                     ORDER BY revision DESC LIMIT ?
                     """)) {
            statement.setString(1, id);
            statement.setInt(2, safeLimit);
            try (ResultSet result = statement.executeQuery()) {
                var revisions = new java.util.ArrayList<PolicyRevision>();
                while (result.next()) revisions.add(new PolicyRevision(
                        readRule(result, "rule_id", currency), result.getLong("revision"),
                        result.getString("changed_by"), result.getString("change_reason"),
                        result.getTimestamp("created_at").toInstant()));
                return List.copyOf(revisions);
            }
        } catch (RuntimeException exception) {
            throw new SQLException("Invalid financial policy history", exception);
        }
    }

    private static long lockRevision(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision FROM aurum_financial_rules WHERE id = ? FOR UPDATE")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private static void upsertCurrent(Connection connection, FinancialRule rule, long revision,
                                      String actor, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_financial_rules(id, rule_kind, handler_version, categories_json,
                    definition_json, priority, enabled, effective_from, effective_until,
                    revision, updated_by, update_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE rule_kind = VALUES(rule_kind),
                    handler_version = VALUES(handler_version), categories_json = VALUES(categories_json),
                    definition_json = VALUES(definition_json), priority = VALUES(priority),
                    enabled = VALUES(enabled), effective_from = VALUES(effective_from),
                    effective_until = VALUES(effective_until), revision = VALUES(revision),
                    updated_by = VALUES(updated_by), update_reason = VALUES(update_reason)
                """)) {
            statement.setString(1, rule.id());
            bindRule(statement, rule, 2);
            statement.setLong(10, revision);
            statement.setString(11, actor);
            statement.setString(12, reason);
            statement.executeUpdate();
        }
    }

    /** Create-only path used by the optimistic editor; never overwrites a racing create. */
    private static void insertCurrent(Connection connection, FinancialRule rule, long revision,
                                      String actor, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_financial_rules(id, rule_kind, handler_version, categories_json,
                    definition_json, priority, enabled, effective_from, effective_until,
                    revision, updated_by, update_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, rule.id());
            bindRule(statement, rule, 2);
            statement.setLong(10, revision);
            statement.setString(11, actor);
            statement.setString(12, reason);
            statement.executeUpdate();
        }
    }

    private static void insertRevision(Connection connection, FinancialRule rule, long revision,
                                       String actor, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_financial_rule_revisions(rule_id, revision, rule_kind,
                    handler_version, categories_json, definition_json, priority, enabled,
                    effective_from, effective_until, changed_by, change_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, rule.id());
            statement.setLong(2, revision);
            bindRule(statement, rule, 3);
            statement.setString(11, actor);
            statement.setString(12, reason);
            statement.executeUpdate();
        }
    }

    private static void bindRule(PreparedStatement statement, FinancialRule rule, int first) throws SQLException {
        statement.setString(first, rule.kind().name());
        statement.setInt(first + 1, rule.handlerVersion());
        statement.setString(first + 2, JSON.toJson(rule.categories().stream().map(Enum::name).sorted().toList()));
        statement.setString(first + 3, JSON.toJson(rule.definition()));
        statement.setInt(first + 4, rule.priority());
        statement.setBoolean(first + 5, rule.enabled());
        setInstant(statement, first + 6, rule.effectiveFrom());
        setInstant(statement, first + 7, rule.effectiveUntil());
    }

    private static FinancialRule readRule(ResultSet result, String idColumn, CurrencySpec currency)
            throws SQLException {
        String[] names = JSON.fromJson(result.getString("categories_json"), String[].class);
        Set<TransactionCategory> categories = new LinkedHashSet<>();
        Arrays.stream(names).map(TransactionCategory::valueOf).forEach(categories::add);
        Map<String, String> definition = JSON.fromJson(result.getString("definition_json"), STRING_MAP);
        FinancialRule rule = new FinancialRule(
                result.getString(idColumn), result.getLong("revision"),
                PolicyKind.valueOf(result.getString("rule_kind")),
                result.getInt("handler_version"), categories, definition,
                result.getInt("priority"), result.getBoolean("enabled"),
                instant(result, "effective_from"), instant(result, "effective_until"));
        return PolicyValidator.validate(rule, currency);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) statement.setTimestamp(index, null);
        else statement.setTimestamp(index, Timestamp.from(value));
    }

    private static void validateText(String value, int limit, String label) {
        if (value == null || value.isBlank() || value.length() > limit
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }
}
