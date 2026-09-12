package ovh.aurumgg.core.engine.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;
import ovh.aurumgg.core.engine.LedgerCommit;
import ovh.aurumgg.core.engine.LedgerPosting;
import ovh.aurumgg.core.engine.LedgerRepository;
import ovh.aurumgg.core.engine.TransactionPlan;
import ovh.aurumgg.core.engine.TransactionIntent;

/** MariaDB ledger implementation; every balance mutation and audit row commits together. */
public final class MariaDbLedgerRepository implements LedgerRepository {
    private final DataSource dataSource;
    private final Clock clock;

    public MariaDbLedgerRepository(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Override
    public void initialize(CurrencySpec currency) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO aurum_currencies(id, display_name, symbol, amount_scale, active)
                        VALUES (?, ?, ?, ?, TRUE)
                        ON DUPLICATE KEY UPDATE display_name = VALUES(display_name),
                            symbol = VALUES(symbol), active = TRUE
                        """)) {
                    statement.setString(1, currency.id());
                    statement.setString(2, currency.displayName());
                    statement.setString(3, currency.symbol());
                    statement.setInt(4, currency.scale());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT amount_scale FROM aurum_currencies WHERE id = ?")) {
                    statement.setString(1, currency.id());
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || result.getInt(1) != currency.scale()) {
                            throw new SQLException("Configured currency scale differs from the existing ledger");
                        }
                    }
                }
                ensureAccount(connection, AccountId.globalTreasury(), currency);
                ensureAccount(connection, new AccountId(AccountType.SYSTEM_SOURCE, "global"), currency);
                ensureAccount(connection, new AccountId(AccountType.SYSTEM_SINK, "global"), currency);
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    @Override
    public Optional<BigDecimal> balance(AccountId account, CurrencySpec currency) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT balance FROM aurum_accounts
                     WHERE account_type = ? AND reference_id = ? AND currency_id = ?
                     """)) {
            bindAccount(statement, account, currency, 1);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getBigDecimal(1).setScale(currency.scale())) : Optional.empty();
            }
        }
    }

    @Override
    public List<BalanceSnapshot> richest(CurrencySpec currency, int limit)
            throws SQLException {
        // Только PLAYER: казна или эскроу с большим остатком — не новость, а на
        // доске богатства она делает доску бессмысленной.
        String sql = "SELECT reference_id, balance FROM aurum_accounts "
                + "WHERE currency_id = ? AND account_type = 'PLAYER' AND balance > 0 "
                + "ORDER BY balance DESC, reference_id ASC LIMIT ?";
        List<BalanceSnapshot> result = new ArrayList<>();
        Instant now = Instant.now(clock);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setReadOnly(true);
            statement.setString(1, currency.id());
            statement.setInt(2, Math.clamp(limit, 1, 200));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID player;
                    try {
                        player = UUID.fromString(rows.getString("reference_id"));
                    } catch (IllegalArgumentException notAPlayer) {
                        // Счёт типа PLAYER с адресом, который не UUID, — это
                        // испорченная строка, а не игрок. Пропускаем молча:
                        // доска богатства не то место, где о ней докладывать.
                        continue;
                    }
                    result.add(new BalanceSnapshot(AccountId.player(player), currency,
                            rows.getBigDecimal("balance").setScale(currency.scale()), now, true));
                }
            }
        }
        return List.copyOf(result);
    }

    @Override
    public GlobalEconomySnapshot globalSnapshot(CurrencySpec currency) throws SQLException {
        String sql = """
                SELECT
                    COALESCE(SUM(CASE WHEN account_type = 'TREASURY' AND reference_id = 'global'
                        THEN balance ELSE 0 END), 0) AS treasury,
                    COALESCE(SUM(CASE WHEN account_type NOT IN ('SYSTEM_SOURCE', 'SYSTEM_SINK')
                        THEN balance ELSE 0 END), 0) AS supply
                FROM aurum_accounts WHERE currency_id = ?
                """;
        BigDecimal treasury;
        BigDecimal supply;
        BigDecimal taxes;
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, currency.id());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    treasury = result.getBigDecimal("treasury").setScale(currency.scale());
                    supply = result.getBigDecimal("supply").setScale(currency.scale());
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COALESCE(SUM(tax_amount), 0) FROM aurum_transactions
                    WHERE currency_id = ? AND status = 'COMMITTED'
                    """)) {
                statement.setString(1, currency.id());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    taxes = result.getBigDecimal(1).setScale(currency.scale());
                }
            }
            connection.commit();
        }
        return new GlobalEconomySnapshot(currency, treasury, supply, taxes, Instant.now(clock), true, true);
    }

    @Override
    public LedgerCommit commit(TransactionPlan plan, CurrencySpec currency) throws SQLException {
        return commit(plan, currency, null);
    }

    @Override
    public LedgerCommit commit(TransactionPlan plan, CurrencySpec currency, UUID capturedHold) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            UUID transactionId = UUID.randomUUID();
            try {
                if (!insertPendingTransaction(connection, transactionId, plan, currency)) {
                    connection.rollback();
                    return findDuplicate(plan, currency);
                }

                List<LedgerPosting> ordered = plan.postings().stream()
                        .sorted(Comparator.comparing(posting -> posting.account().stableKey()))
                        .toList();
                for (LedgerPosting posting : ordered) ensureAccount(connection, posting.account(), currency);

                Map<AccountId, LockedAccount> locked = new LinkedHashMap<>();
                for (LedgerPosting posting : ordered) {
                    locked.put(posting.account(), lockAccount(connection, posting.account(), currency));
                }

                Map<AccountId, BigDecimal> afterBalances = new LinkedHashMap<>();
                for (LedgerPosting posting : ordered) {
                    LockedAccount account = locked.get(posting.account());
                    BigDecimal after = account.balance().add(posting.amount()).setScale(currency.scale());
                    BigDecimal reserved = posting.amount().signum() < 0
                            ? MariaDbHoldRepository.activeHeld(connection, account.id(), currency.id(), capturedHold)
                            : BigDecimal.ZERO;
                    if (after.subtract(reserved).signum() < 0
                            && posting.account().type() != AccountType.SYSTEM_SOURCE) {
                        String reason = "Insufficient funds in " + posting.account().stableKey();
                        markRejected(connection, transactionId, reason);
                        connection.commit();
                        return rejected(plan, transactionId, LedgerCommit.Status.INSUFFICIENT_FUNDS,
                                reason, currency);
                    }
                    afterBalances.put(posting.account(), after);
                }
                for (LedgerPosting posting : ordered) {
                    LockedAccount account = locked.get(posting.account());
                    BigDecimal after = afterBalances.get(posting.account());
                    updateBalance(connection, account.id(), after);
                    insertPosting(connection, transactionId, account.id(), posting.amount(), after);
                    locked.put(posting.account(), new LockedAccount(account.id(), after));
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE aurum_transactions SET status = 'COMMITTED', committed_at = CURRENT_TIMESTAMP(6)
                        WHERE id = ?
                        """)) {
                    statement.setString(1, transactionId.toString());
                    statement.executeUpdate();
                }
                insertOutbox(connection, transactionId, plan);
                connection.commit();
                return new LedgerCommit(
                        LedgerCommit.Status.COMMITTED,
                        transactionId,
                        currency.requireAmount(plan.request().amount()),
                        plan.targetCredit(),
                        plan.taxCredit(),
                        balanceAfter(locked, plan.request().from(), currency),
                        balanceAfter(locked, plan.request().to(), currency),
                        "Committed",
                        balancesAfter(locked, currency)
                );
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        }
    }

    @Override
    public boolean transactionCommitted(String idempotencyKey) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1 FROM aurum_transactions
                     WHERE idempotency_key = ? AND status = 'COMMITTED'
                     """)) {
            statement.setString(1, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean insertPendingTransaction(Connection connection, UUID id, TransactionPlan plan,
                                             CurrencySpec currency) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_transactions(
                    id, idempotency_key, request_hash, currency_id, category, status, gross_amount,
                    net_amount, tax_amount, tax_rule_id, policy_rule_ids_json,
                    policy_amounts_json, metadata_json)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, plan.request().idempotencyKey());
            statement.setString(3, TransactionIntent.hash(plan.request()));
            statement.setString(4, currency.id());
            statement.setString(5, plan.request().category().name());
            statement.setBigDecimal(6, currency.requireAmount(plan.request().amount()));
            statement.setBigDecimal(7, plan.targetCredit());
            statement.setBigDecimal(8, plan.taxCredit());
            statement.setString(9, plan.appliedRuleId());
            statement.setString(10, plan.appliedRuleIds().isEmpty() ? null : jsonArray(plan.appliedRuleIds()));
            statement.setString(11, plan.policyAmounts().isEmpty() ? null : jsonAmounts(plan.policyAmounts()));
            statement.setString(12, jsonObject(plan.request().metadata()));
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            if ("23000".equals(exception.getSQLState())) return false;
            throw exception;
        }
    }

    private LedgerCommit findDuplicate(TransactionPlan plan, CurrencySpec currency) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, request_hash, gross_amount, net_amount, tax_amount, status, failure_reason
                     FROM aurum_transactions WHERE idempotency_key = ?
                     """)) {
            statement.setString(1, plan.request().idempotencyKey());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException(
                        "Duplicate transaction disappeared: " + plan.request().idempotencyKey());
                String storedHash = result.getString("request_hash");
                if (storedHash != null && !storedHash.equals(TransactionIntent.hash(plan.request()))) {
                    BigDecimal zero = BigDecimal.ZERO.setScale(currency.scale());
                    return new LedgerCommit(LedgerCommit.Status.REJECTED,
                            UUID.fromString(result.getString("id")),
                            currency.requireAmount(plan.request().amount()), zero, zero, zero, zero,
                            "IDEMPOTENCY_KEY_REUSED");
                }
                String storedStatus = result.getString("status");
                LedgerCommit.Status status = "REJECTED".equals(storedStatus)
                        ? LedgerCommit.Status.REJECTED : LedgerCommit.Status.DUPLICATE;
                return new LedgerCommit(
                        status,
                        UUID.fromString(result.getString("id")),
                        result.getBigDecimal("gross_amount").setScale(currency.scale()),
                        result.getBigDecimal("net_amount").setScale(currency.scale()),
                        result.getBigDecimal("tax_amount").setScale(currency.scale()),
                        BigDecimal.ZERO.setScale(currency.scale()),
                        BigDecimal.ZERO.setScale(currency.scale()),
                        "REJECTED".equals(storedStatus)
                                ? result.getString("failure_reason")
                                : "Existing transaction status: " + storedStatus
                );
            }
        }
    }

    private static LedgerCommit rejected(TransactionPlan plan, UUID transactionId, LedgerCommit.Status status,
                                         String message, CurrencySpec currency) {
        BigDecimal zero = BigDecimal.ZERO.setScale(currency.scale());
        return new LedgerCommit(status, transactionId, currency.requireAmount(plan.request().amount()),
                plan.targetCredit(), plan.taxCredit(), zero, zero, message);
    }

    private static void markRejected(Connection connection, UUID transactionId, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE aurum_transactions SET status = 'REJECTED', failure_reason = ?,
                    committed_at = CURRENT_TIMESTAMP(6) WHERE id = ?
                """)) {
            statement.setString(1, reason);
            statement.setString(2, transactionId.toString());
            if (statement.executeUpdate() != 1) throw new SQLException("Could not mark transaction rejected");
        }
    }

    private static void ensureAccount(Connection connection, AccountId account, CurrencySpec currency)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_accounts(account_type, reference_id, currency_id, balance)
                VALUES (?, ?, ?, 0) ON DUPLICATE KEY UPDATE id = id
                """)) {
            bindAccount(statement, account, currency, 1);
            statement.executeUpdate();
        }
    }

    private static LockedAccount lockAccount(Connection connection, AccountId account, CurrencySpec currency)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, balance FROM aurum_accounts
                WHERE account_type = ? AND reference_id = ? AND currency_id = ? FOR UPDATE
                """)) {
            bindAccount(statement, account, currency, 1);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Account was not created: " + account.stableKey());
                return new LockedAccount(result.getLong("id"), result.getBigDecimal("balance"));
            }
        }
    }

    private static void updateBalance(Connection connection, long accountId, BigDecimal balance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE aurum_accounts SET balance = ?, version = version + 1 WHERE id = ?
                """)) {
            statement.setBigDecimal(1, balance);
            statement.setLong(2, accountId);
            if (statement.executeUpdate() != 1) throw new SQLException("Account update affected no rows");
        }
    }

    private static void insertPosting(Connection connection, UUID transactionId, long accountId,
                                      BigDecimal amount, BigDecimal after) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_ledger_entries(transaction_id, account_id, amount, balance_after)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, transactionId.toString());
            statement.setLong(2, accountId);
            statement.setBigDecimal(3, amount);
            statement.setBigDecimal(4, after);
            statement.executeUpdate();
        }
    }

    private static void insertOutbox(Connection connection, UUID transactionId, TransactionPlan plan)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_outbox(event_id, event_type, payload_json) VALUES (?, 'economy.transaction', ?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, "{\"transactionId\":\"" + transactionId + "\",\"idempotencyKey\":\""
                    + jsonEscape(plan.request().idempotencyKey()) + "\"}");
            statement.executeUpdate();
        }
    }

    private static void bindAccount(PreparedStatement statement, AccountId account,
                                    CurrencySpec currency, int first) throws SQLException {
        statement.setString(first, account.type().name());
        statement.setString(first + 1, account.reference());
        statement.setString(first + 2, currency.id());
    }

    private static String jsonObject(Map<String, String> values) {
        List<String> entries = new ArrayList<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> entries.add(
                "\"" + jsonEscape(entry.getKey()) + "\":\"" + jsonEscape(entry.getValue()) + "\""));
        return "{" + String.join(",", entries) + "}";
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.toString();
    }

    private static void rollback(Connection connection, SQLException original) {
        try { connection.rollback(); } catch (SQLException rollback) { original.addSuppressed(rollback); }
    }

    private static BigDecimal balanceAfter(Map<AccountId, LockedAccount> locked, AccountId account,
                                           CurrencySpec currency) {
        LockedAccount value = locked.get(account);
        return value == null ? BigDecimal.ZERO.setScale(currency.scale()) : value.balance().setScale(currency.scale());
    }


    private static String jsonArray(List<String> values) {
        return "[" + values.stream().map(value -> "\"" + jsonEscape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private static String jsonAmounts(Map<String, BigDecimal> values) {
        List<String> entries = new ArrayList<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> entries.add(
                "\"" + jsonEscape(entry.getKey()) + "\":\"" + entry.getValue().toPlainString() + "\""));
        return "{" + String.join(",", entries) + "}";
    }

    private static Map<AccountId, BigDecimal> balancesAfter(Map<AccountId, LockedAccount> locked,
                                                             CurrencySpec currency) {
        Map<AccountId, BigDecimal> balances = new LinkedHashMap<>();
        locked.forEach((account, value) -> balances.put(account, value.balance().setScale(currency.scale())));
        return balances;
    }

    private record LockedAccount(long id, BigDecimal balance) {}
}
