package ovh.aurumgg.core.engine.db;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.HoldSnapshot;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.engine.HoldRepository;

public final class MariaDbHoldRepository implements HoldRepository {
    private static final Gson JSON = new Gson();
    private static final java.lang.reflect.Type STRING_MAP = new TypeToken<Map<String, String>>() {}.getType();
    private final DataSource dataSource;
    public MariaDbHoldRepository(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public HoldResult reserve(HoldSnapshot hold) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                Optional<HoldSnapshot> duplicate = find(connection, "idempotency_key", hold.idempotencyKey(),
                        hold.currency());
                if (duplicate.isPresent()) {
                    connection.rollback();
                    if (!sameReservation(duplicate.orElseThrow(), hold)) {
                        return new HoldResult(HoldResult.Status.REJECTED, duplicate,
                                "Idempotency key belongs to a different hold intent");
                    }
                    return new HoldResult(HoldResult.Status.DUPLICATE, duplicate, "Existing hold");
                }
                ensureAccount(connection, hold.from(), hold.currency());
                LockedAccount account = lockAccount(connection, hold.from(), hold.currency());
                duplicate = find(connection, "idempotency_key", hold.idempotencyKey(), hold.currency());
                if (duplicate.isPresent()) {
                    connection.rollback();
                    if (!sameReservation(duplicate.orElseThrow(), hold)) {
                        return new HoldResult(HoldResult.Status.REJECTED, duplicate,
                                "Idempotency key belongs to a different hold intent");
                    }
                    return new HoldResult(HoldResult.Status.DUPLICATE, duplicate, "Existing hold");
                }
                BigDecimal held = activeHeld(connection, account.id(), hold.currency().id(), null);
                boolean funded = hold.from().type() == AccountType.SYSTEM_SOURCE
                        || account.balance().subtract(held).compareTo(hold.reservedAmount()) >= 0;
                HoldSnapshot stored = withStatus(hold,
                        funded ? HoldSnapshot.Status.HELD : HoldSnapshot.Status.REJECTED);
                insert(connection, account.id(), stored);
                connection.commit();
                return new HoldResult(funded ? HoldResult.Status.SUCCESS : HoldResult.Status.INSUFFICIENT_FUNDS,
                        Optional.of(stored), funded ? "Funds reserved" : "Insufficient available balance");
            } catch (SQLException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                // Two Core instances can race on the same key while locking
                // different source accounts. The unique index is the final
                // arbiter; turn its loser into the same semantic answer as the
                // ordinary duplicate path instead of a transient outage.
                if (duplicateKey(exception)) {
                    Optional<HoldSnapshot> duplicate = find(hold.idempotencyKey(), hold.currency());
                    if (duplicate.isPresent()) {
                        return new HoldResult(sameReservation(duplicate.orElseThrow(), hold)
                                ? HoldResult.Status.DUPLICATE : HoldResult.Status.REJECTED,
                                duplicate, sameReservation(duplicate.orElseThrow(), hold)
                                        ? "Existing hold"
                                        : "Idempotency key belongs to a different hold intent");
                    }
                }
                throw exception;
            } catch (RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        }
    }

    private static boolean sameReservation(HoldSnapshot first, HoldSnapshot second) {
        return first.from().equals(second.from()) && first.to().equals(second.to())
                && first.currency().id().equals(second.currency().id())
                && first.amount().compareTo(second.amount()) == 0
                && first.reservedAmount().compareTo(second.reservedAmount()) == 0
                && first.category() == second.category()
                && first.purpose().equals(second.purpose())
                && first.referenceId().equals(second.referenceId())
                && first.expiresAt().equals(second.expiresAt())
                && first.metadata().equals(second.metadata());
    }

    private static boolean duplicateKey(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if (current.getErrorCode() == 1062) return true;
        }
        return false;
    }

    @Override public Optional<HoldSnapshot> find(UUID id, CurrencySpec currency) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, "h.id", id.toString(), currency);
        }
    }
    @Override public Optional<HoldSnapshot> find(String key, CurrencySpec currency) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, "h.idempotency_key", key, currency);
        }
    }

    @Override
    public List<HoldSnapshot> recent(CurrencySpec currency, int limit) throws SQLException {
        List<HoldSnapshot> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     select() + " WHERE h.currency_id=? ORDER BY h.created_at DESC,h.id DESC LIMIT ?")) {
            connection.setReadOnly(true);
            statement.setString(1, currency.id());
            statement.setInt(2, Math.clamp(limit, 1, 200));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(read(rows, currency));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public HoldResult resolve(UUID id, HoldSnapshot.Status desired, CurrencySpec currency) throws SQLException {
        if (desired != HoldSnapshot.Status.CAPTURED && desired != HoldSnapshot.Status.RELEASED
                && desired != HoldSnapshot.Status.EXPIRED) throw new IllegalArgumentException("Invalid resolution");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<HoldSnapshot> found = findForUpdate(connection, id, currency);
                if (found.isEmpty()) { connection.rollback(); return new HoldResult(HoldResult.Status.NOT_FOUND,
                        Optional.empty(), "Hold was not found"); }
                HoldSnapshot current = found.get();
                if (current.status() == desired) { connection.rollback(); return new HoldResult(
                        HoldResult.Status.DUPLICATE, found, "Hold already resolved"); }
                if (current.status() != HoldSnapshot.Status.HELD) { connection.rollback(); return new HoldResult(
                        HoldResult.Status.REJECTED, found, "Hold is already resolved"); }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE aurum_holds SET status=?, resolved_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='HELD'
                        """)) {
                    statement.setString(1, desired.name());
                    statement.setString(2, id.toString());
                    if (statement.executeUpdate() != 1) throw new SQLException("Hold resolution lost its lock");
                }
                connection.commit();
                return new HoldResult(HoldResult.Status.SUCCESS,
                        Optional.of(withStatus(current, desired)), "Hold " + desired.name().toLowerCase());
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        }
    }

    private static void insert(Connection connection, long accountId, HoldSnapshot hold) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_holds(id,idempotency_key,account_id,currency_id,amount,purpose,reference_id,
                    status,expires_at,target_account_type,target_reference_id,request_amount,
                    transaction_category,metadata_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, hold.id().toString());
            statement.setString(2, hold.idempotencyKey());
            statement.setLong(3, accountId);
            statement.setString(4, hold.currency().id());
            statement.setBigDecimal(5, hold.reservedAmount());
            statement.setString(6, hold.purpose());
            statement.setString(7, hold.referenceId());
            statement.setString(8, hold.status().name());
            statement.setTimestamp(9, java.sql.Timestamp.from(hold.expiresAt()));
            statement.setString(10, hold.to().type().name());
            statement.setString(11, hold.to().reference());
            statement.setBigDecimal(12, hold.amount());
            statement.setString(13, hold.category().name());
            statement.setString(14, JSON.toJson(hold.metadata()));
            statement.executeUpdate();
        }
    }

    private static Optional<HoldSnapshot> find(Connection connection, String column, String value,
                                                CurrencySpec currency) throws SQLException {
        if (!column.equals("h.id") && !column.equals("h.idempotency_key") && !column.equals("idempotency_key")) {
            throw new IllegalArgumentException("Unsafe hold lookup column");
        }
        String qualified = column.equals("idempotency_key") ? "h.idempotency_key" : column;
        try (PreparedStatement statement = connection.prepareStatement(
                select() + " WHERE " + qualified + "=? AND h.currency_id=?")) {
            statement.setString(1, value);
            statement.setString(2, currency.id());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result, currency)) : Optional.empty();
            }
        }
    }
    private static Optional<HoldSnapshot> findForUpdate(Connection connection, UUID id,
                                                         CurrencySpec currency) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                select() + " WHERE h.id=? AND h.currency_id=? FOR UPDATE")) {
            statement.setString(1, id.toString());
            statement.setString(2, currency.id());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result, currency)) : Optional.empty();
            }
        }
    }
    private static String select() { return """
            SELECT h.*,a.account_type source_type,a.reference_id source_reference
            FROM aurum_holds h JOIN aurum_accounts a ON a.id=h.account_id
            """; }
    private static HoldSnapshot read(ResultSet result, CurrencySpec currency) throws SQLException {
        Map<String, String> metadata = JSON.fromJson(result.getString("metadata_json"), STRING_MAP);
        return new HoldSnapshot(UUID.fromString(result.getString("id")), result.getString("idempotency_key"),
                new AccountId(AccountType.valueOf(result.getString("source_type")), result.getString("source_reference")),
                new AccountId(AccountType.valueOf(result.getString("target_account_type")), result.getString("target_reference_id")),
                currency, result.getBigDecimal("request_amount"), result.getBigDecimal("amount"),
                TransactionCategory.valueOf(result.getString("transaction_category")), result.getString("purpose"),
                result.getString("reference_id"), HoldSnapshot.Status.valueOf(result.getString("status")),
                result.getTimestamp("created_at").toInstant(), result.getTimestamp("expires_at").toInstant(),
                metadata == null ? Map.of() : metadata);
    }
    private static HoldSnapshot withStatus(HoldSnapshot hold, HoldSnapshot.Status status) {
        return new HoldSnapshot(hold.id(), hold.idempotencyKey(), hold.from(), hold.to(), hold.currency(),
                hold.amount(), hold.reservedAmount(), hold.category(), hold.purpose(), hold.referenceId(), status,
                hold.createdAt(), hold.expiresAt(), hold.metadata());
    }
    private static void ensureAccount(Connection connection, AccountId account, CurrencySpec currency)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_accounts(account_type,reference_id,currency_id,balance)
                VALUES (?,?,?,0) ON DUPLICATE KEY UPDATE id=id
                """)) {
            statement.setString(1, account.type().name()); statement.setString(2, account.reference());
            statement.setString(3, currency.id()); statement.executeUpdate();
        }
    }
    private static LockedAccount lockAccount(Connection connection, AccountId account, CurrencySpec currency)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id,balance FROM aurum_accounts
                WHERE account_type=? AND reference_id=? AND currency_id=? FOR UPDATE
                """)) {
            statement.setString(1, account.type().name()); statement.setString(2, account.reference());
            statement.setString(3, currency.id());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Hold account was not created");
                return new LockedAccount(result.getLong(1), result.getBigDecimal(2));
            }
        }
    }
    static BigDecimal activeHeld(Connection connection, long accountId, String currencyId, UUID excluded)
            throws SQLException {
        BigDecimal total = BigDecimal.ZERO;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id,amount FROM aurum_holds
                WHERE account_id=? AND currency_id=? AND status='HELD' AND expires_at>CURRENT_TIMESTAMP(6)
                FOR UPDATE
                """)) {
            statement.setLong(1, accountId); statement.setString(2, currencyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) if (excluded == null || !excluded.toString().equals(result.getString(1)))
                    total = total.add(result.getBigDecimal(2));
            }
        }
        return total;
    }
    private record LockedAccount(long id, BigDecimal balance) {}
}
