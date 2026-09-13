package ovh.aurumgg.core.engine.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.ManagedAccount;
import ovh.aurumgg.core.api.ManagedAccountMember;
import ovh.aurumgg.core.api.ManagedAccountPage;
import ovh.aurumgg.core.api.ManagedAccountQuery;
import ovh.aurumgg.core.api.ManagedAccountRegistration;
import ovh.aurumgg.core.api.ManagedAccountStatus;
import ovh.aurumgg.core.engine.AccountRegistryRepository;

/** MariaDB metadata registry. Balances are read from aurum_accounts, never copied. */
public final class MariaDbAccountRegistryRepository implements AccountRegistryRepository {
    private final DataSource dataSource;

    public MariaDbAccountRegistryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public ManagedAccountPage list(ManagedAccountQuery query, Map<String, CurrencySpec> currencies)
            throws SQLException {
        Filter filter = filter(query);
        try (Connection connection = dataSource.getConnection()) {
            long total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM aurum_account_profiles p " + filter.where())) {
                filter.bind(statement, 1);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    total = result.getLong(1);
                }
            }
            List<ProfileRow> profiles = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT p.profile_key, p.profile_type, p.display_name, p.purpose, p.owner_kind, "
                            + "p.owner_id, p.founder_uuid, p.source_plugin, p.linked_object_type, "
                            + "p.linked_object_id, p.status, p.close_destination_profile, p.technical, "
                            + "p.created_at, p.updated_at, p.closed_at FROM aurum_account_profiles p "
                            + filter.where()
                            + " ORDER BY p.status, p.profile_type, p.display_name, p.profile_key LIMIT ? OFFSET ?")) {
                int next = filter.bind(statement, 1);
                statement.setInt(next++, query.limit());
                statement.setInt(next, query.offset());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) profiles.add(profileRow(result));
                }
            }
            if (profiles.isEmpty()) return new ManagedAccountPage(List.of(), query.offset(), query.limit(), total);
            List<String> keys = profiles.stream().map(ProfileRow::profileKey).toList();
            Map<String, List<ManagedAccountMember>> members = members(connection, keys);
            Map<String, Map<String, BigDecimal>> balances = bulkBalances(connection, keys, currencies);
            List<ManagedAccount> accounts = profiles.stream()
                    .map(profile -> profile.toAccount(members.getOrDefault(profile.profileKey(), List.of()),
                            balances.getOrDefault(profile.profileKey(), zeroBalances(currencies))))
                    .toList();
            return new ManagedAccountPage(accounts, query.offset(), query.limit(), total);
        }
    }

    @Override
    public Optional<ManagedAccount> find(String profileKey, Map<String, CurrencySpec> currencies)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, normalizeKey(profileKey), currencies);
        }
    }

    private Optional<ManagedAccount> find(Connection connection, String key,
                                          Map<String, CurrencySpec> currencies) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_key, profile_type, display_name, purpose, owner_kind, owner_id,
                    founder_uuid, source_plugin, linked_object_type, linked_object_id, status,
                    close_destination_profile, technical, created_at, updated_at, closed_at
                FROM aurum_account_profiles WHERE profile_key = ?
                """)) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                List<ManagedAccountMember> members = members(connection, key);
                Map<String, BigDecimal> balances = balances(connection, members, currencies);
                Timestamp closed = row.getTimestamp("closed_at");
                return Optional.of(new ManagedAccount(
                        row.getString("profile_key"), row.getString("profile_type"),
                        row.getString("display_name"), row.getString("purpose"),
                        row.getString("owner_kind"), row.getString("owner_id"),
                        value(row, "founder_uuid"), row.getString("source_plugin"),
                        row.getString("linked_object_type"), row.getString("linked_object_id"),
                        ManagedAccountStatus.valueOf(row.getString("status")),
                        row.getString("close_destination_profile"), row.getBoolean("technical"),
                        members, balances, row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("updated_at").toInstant(),
                        closed == null ? null : closed.toInstant()));
            }
        }
    }

    private static List<ManagedAccountMember> members(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account_type, reference_id, member_role, display_order
                FROM aurum_account_profile_members WHERE profile_key = ?
                ORDER BY display_order, member_role
                """)) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                List<ManagedAccountMember> members = new ArrayList<>();
                while (result.next()) members.add(new ManagedAccountMember(
                        new AccountId(AccountType.valueOf(result.getString("account_type")),
                                result.getString("reference_id")),
                        result.getString("member_role"), result.getInt("display_order")));
                return List.copyOf(members);
            }
        }
    }

    private static Map<String, List<ManagedAccountMember>> members(Connection connection, List<String> keys)
            throws SQLException {
        Map<String, List<ManagedAccountMember>> values = new LinkedHashMap<>();
        keys.forEach(key -> values.put(key, new ArrayList<>()));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_key, account_type, reference_id, member_role, display_order
                FROM aurum_account_profile_members WHERE profile_key IN (
                """ + placeholders(keys.size()) + ") ORDER BY profile_key, display_order, member_role")) {
            bindKeys(statement, keys);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.get(result.getString("profile_key")).add(new ManagedAccountMember(
                        new AccountId(AccountType.valueOf(result.getString("account_type")),
                                result.getString("reference_id")),
                        result.getString("member_role"), result.getInt("display_order")));
            }
        }
        values.replaceAll((key, members) -> List.copyOf(members));
        return Map.copyOf(values);
    }

    private static Map<String, BigDecimal> balances(Connection connection,
                                                     List<ManagedAccountMember> members,
                                                     Map<String, CurrencySpec> currencies) throws SQLException {
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        currencies.forEach((id, currency) -> balances.put(id, BigDecimal.ZERO.setScale(currency.scale())));
        if (members.isEmpty()) return Map.copyOf(balances);
        String conditions = String.join(" OR ", java.util.Collections.nCopies(members.size(),
                "(account_type = ? AND reference_id = ?)"));
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT currency_id, SUM(balance) balance FROM aurum_accounts WHERE " + conditions
                        + " GROUP BY currency_id")) {
            int index = 1;
            for (ManagedAccountMember member : members) {
                statement.setString(index++, member.account().type().name());
                statement.setString(index++, member.account().reference());
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    CurrencySpec currency = currencies.get(result.getString("currency_id"));
                    if (currency != null) balances.put(currency.id(), currency.requireAmount(result.getBigDecimal("balance")));
                }
            }
        }
        return Map.copyOf(balances);
    }

    private static Map<String, Map<String, BigDecimal>> bulkBalances(Connection connection, List<String> keys,
                                                                      Map<String, CurrencySpec> currencies)
            throws SQLException {
        Map<String, Map<String, BigDecimal>> values = new LinkedHashMap<>();
        keys.forEach(key -> values.put(key, new LinkedHashMap<>(zeroBalances(currencies))));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT m.profile_key, a.currency_id, SUM(a.balance) balance
                FROM aurum_account_profile_members m
                JOIN aurum_accounts a ON a.account_type = m.account_type AND a.reference_id = m.reference_id
                WHERE m.profile_key IN (
                """ + placeholders(keys.size()) + ") GROUP BY m.profile_key, a.currency_id")) {
            bindKeys(statement, keys);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    CurrencySpec currency = currencies.get(result.getString("currency_id"));
                    if (currency != null) values.get(result.getString("profile_key")).put(currency.id(),
                            currency.requireAmount(result.getBigDecimal("balance")));
                }
            }
        }
        values.replaceAll((key, balances) -> Map.copyOf(balances));
        return Map.copyOf(values);
    }

    private static Map<String, BigDecimal> zeroBalances(Map<String, CurrencySpec> currencies) {
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        currencies.forEach((id, currency) -> balances.put(id, BigDecimal.ZERO.setScale(currency.scale())));
        return Map.copyOf(balances);
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static void bindKeys(PreparedStatement statement, List<String> keys) throws SQLException {
        for (int index = 0; index < keys.size(); index++) statement.setString(index + 1, keys.get(index));
    }

    private static ProfileRow profileRow(ResultSet row) throws SQLException {
        Timestamp closed = row.getTimestamp("closed_at");
        return new ProfileRow(row.getString("profile_key"), row.getString("profile_type"),
                row.getString("display_name"), row.getString("purpose"), row.getString("owner_kind"),
                row.getString("owner_id"), value(row, "founder_uuid"), row.getString("source_plugin"),
                row.getString("linked_object_type"), row.getString("linked_object_id"),
                ManagedAccountStatus.valueOf(row.getString("status")),
                row.getString("close_destination_profile"), row.getBoolean("technical"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                closed == null ? null : closed.toInstant());
    }

    private record ProfileRow(String profileKey, String profileType, String displayName, String purpose,
                              String ownerKind, String ownerId, String founderUuid, String sourcePlugin,
                              String linkedObjectType, String linkedObjectId, ManagedAccountStatus status,
                              String closeDestinationProfile, boolean technical, Instant createdAt,
                              Instant updatedAt, Instant closedAt) {
        ManagedAccount toAccount(List<ManagedAccountMember> members, Map<String, BigDecimal> balances) {
            return new ManagedAccount(profileKey, profileType, displayName, purpose, ownerKind, ownerId,
                    founderUuid, sourcePlugin, linkedObjectType, linkedObjectId, status,
                    closeDestinationProfile, technical, members, balances, createdAt, updatedAt, closedAt);
        }
    }

    @Override
    public WriteResult register(ManagedAccountRegistration request, String intentHash) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                WriteResult replay = replay(connection, request.idempotencyKey(), intentHash, "REGISTER");
                if (replay != null) { connection.commit(); return replay; }
                if (profileExists(connection, request.profileKey())) {
                    insertOperation(connection, request.idempotencyKey(), intentHash, "REGISTER",
                            request.profileKey(), "", "REJECTED", request.actor(), request.reason(),
                            "profile-already-exists");
                    connection.commit();
                    return new WriteResult(WriteStatus.REJECTED, "profile-already-exists");
                }
                insertProfile(connection, request);
                insertMembers(connection, request);
                insertOperation(connection, request.idempotencyKey(), intentHash, "REGISTER",
                        request.profileKey(), "", "COMPLETED", request.actor(), request.reason(), "created");
                connection.commit();
                return new WriteResult(WriteStatus.SUCCESS, "created");
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    @Override
    public WriteResult synchronize(ManagedAccountRegistration request, String intentHash) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                WriteResult replay = replay(connection, request.idempotencyKey(), intentHash, "SYNC");
                if (replay != null) { connection.commit(); return replay; }
                ManagedAccountStatus status = lockStatus(connection, request.profileKey());
                String message;
                if (status == null) {
                    insertProfile(connection, request);
                    insertMembers(connection, request);
                    message = "created";
                } else {
                    if (status == ManagedAccountStatus.CLOSING || status == ManagedAccountStatus.CLOSED) {
                        insertOperation(connection, request.idempotencyKey(), intentHash, "SYNC",
                                request.profileKey(), "", "REJECTED", request.actor(), request.reason(),
                                "profile-not-active");
                        connection.commit();
                        return new WriteResult(WriteStatus.REJECTED, "profile-not-active");
                    }
                    if (!sameMembers(members(connection, request.profileKey()), request.members())) {
                        insertOperation(connection, request.idempotencyKey(), intentHash, "SYNC",
                                request.profileKey(), "", "REJECTED", request.actor(), request.reason(),
                                "ledger-members-immutable");
                        connection.commit();
                        return new WriteResult(WriteStatus.REJECTED, "ledger-members-immutable");
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE aurum_account_profiles SET profile_type = ?, display_name = ?, purpose = ?,
                                owner_kind = ?, owner_id = ?, source_plugin = ?, linked_object_type = ?,
                                linked_object_id = ?, close_destination_profile = ?, technical = ?
                            WHERE profile_key = ?
                            """)) {
                        statement.setString(1, request.profileType());
                        statement.setString(2, request.displayName());
                        statement.setString(3, request.purpose());
                        statement.setString(4, request.ownerKind());
                        statement.setString(5, request.ownerId());
                        statement.setString(6, request.sourcePlugin());
                        statement.setString(7, request.linkedObjectType());
                        statement.setString(8, request.linkedObjectId());
                        statement.setString(9, request.closeDestinationProfile());
                        statement.setBoolean(10, request.technical());
                        statement.setString(11, request.profileKey());
                        statement.executeUpdate();
                    }
                    message = "synchronized";
                }
                insertOperation(connection, request.idempotencyKey(), intentHash, "SYNC", request.profileKey(), "",
                        "COMPLETED", request.actor(), request.reason(), message);
                connection.commit();
                return new WriteResult(WriteStatus.SUCCESS, message);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    private static boolean sameMembers(List<ManagedAccountMember> existing,
                                       List<ManagedAccountMember> requested) {
        java.util.Comparator<ManagedAccountMember> order = java.util.Comparator
                .comparing(ManagedAccountMember::role)
                .thenComparing(member -> member.account().stableKey())
                .thenComparingInt(ManagedAccountMember::displayOrder);
        return existing.stream().sorted(order).toList().equals(requested.stream().sorted(order).toList());
    }

    private static void insertProfile(Connection connection, ManagedAccountRegistration request)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_account_profiles(profile_key, profile_type, display_name, purpose,
                    owner_kind, owner_id, founder_uuid, source_plugin, linked_object_type,
                    linked_object_id, close_destination_profile, technical)
                VALUES (?, ?, ?, ?, ?, ?, NULLIF(?, ''), ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, request.profileKey());
            statement.setString(2, request.profileType());
            statement.setString(3, request.displayName());
            statement.setString(4, request.purpose());
            statement.setString(5, request.ownerKind());
            statement.setString(6, request.ownerId());
            statement.setString(7, request.founderUuid());
            statement.setString(8, request.sourcePlugin());
            statement.setString(9, request.linkedObjectType());
            statement.setString(10, request.linkedObjectId());
            statement.setString(11, request.closeDestinationProfile());
            statement.setBoolean(12, request.technical());
            statement.executeUpdate();
        }
    }

    private static void insertMembers(Connection connection, ManagedAccountRegistration request)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_account_profile_members(profile_key, account_type, reference_id,
                    member_role, display_order) VALUES (?, ?, ?, ?, ?)
                """)) {
            for (ManagedAccountMember member : request.members()) {
                statement.setString(1, request.profileKey());
                statement.setString(2, member.account().type().name());
                statement.setString(3, member.account().reference());
                statement.setString(4, member.role());
                statement.setInt(5, member.displayOrder());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    @Override
    public WriteResult setStatus(String key, String hash, String profileKey,
                                 ManagedAccountStatus expectedFirst, ManagedAccountStatus expectedSecond,
                                 ManagedAccountStatus target, String operation, String actor, String reason)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                WriteResult replay = replay(connection, key, hash, operation);
                if (replay != null) { connection.commit(); return replay; }
                ManagedAccountStatus current = lockStatus(connection, profileKey);
                WriteStatus outcome;
                String message;
                if (current == null) { outcome = WriteStatus.NOT_FOUND; message = "profile-not-found"; }
                else if (current == target) { outcome = WriteStatus.SUCCESS; message = "already-" + target.name().toLowerCase(Locale.ROOT); }
                else if (current != expectedFirst && current != expectedSecond) {
                    outcome = WriteStatus.REJECTED; message = "invalid-status:" + current.name();
                } else {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE aurum_account_profiles SET status = ? WHERE profile_key = ?")) {
                        statement.setString(1, target.name());
                        statement.setString(2, profileKey);
                        statement.executeUpdate();
                    }
                    outcome = WriteStatus.SUCCESS; message = target.name().toLowerCase(Locale.ROOT);
                }
                insertOperation(connection, key, hash, operation, profileKey, "",
                        outcome == WriteStatus.SUCCESS ? "COMPLETED" : "REJECTED", actor, reason, message);
                connection.commit();
                return new WriteResult(outcome, message);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    @Override
    public WriteResult beginClose(String key, String hash, String profileKey, String destination,
                                  String actor, String reason) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Operation existing = operation(connection, key);
                if (existing != null) {
                    connection.commit();
                    if (!existing.hash.equals(hash) || !existing.type.equals("CLOSE")) {
                        return new WriteResult(WriteStatus.CONFLICT, "idempotency-key-reused");
                    }
                    return new WriteResult(existing.status.equals("COMPLETED")
                            ? WriteStatus.DUPLICATE : WriteStatus.SUCCESS, existing.message);
                }
                ManagedAccountStatus current = lockStatus(connection, profileKey);
                String message;
                WriteStatus result;
                if (current == null) { result = WriteStatus.NOT_FOUND; message = "profile-not-found"; }
                else if (current == ManagedAccountStatus.CLOSED) { result = WriteStatus.REJECTED; message = "already-closed"; }
                else if (current == ManagedAccountStatus.CLOSING) { result = WriteStatus.CONFLICT; message = "close-already-in-progress"; }
                else {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE aurum_account_profiles SET status = 'CLOSING',
                                close_destination_profile = ? WHERE profile_key = ?
                            """)) {
                        statement.setString(1, destination);
                        statement.setString(2, profileKey);
                        statement.executeUpdate();
                    }
                    result = WriteStatus.SUCCESS; message = "closing";
                }
                insertOperation(connection, key, hash, "CLOSE", profileKey, destination,
                        result == WriteStatus.SUCCESS ? "PENDING" : "REJECTED", actor, reason, message);
                connection.commit();
                return new WriteResult(result, message);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    @Override
    public void completeClose(String key, String profileKey) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement profile = connection.prepareStatement("""
                    UPDATE aurum_account_profiles SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP(6)
                    WHERE profile_key = ? AND status = 'CLOSING'
                    """)) {
                profile.setString(1, profileKey);
                profile.executeUpdate();
                try (PreparedStatement operation = connection.prepareStatement("""
                        UPDATE aurum_account_operations SET operation_status = 'COMPLETED', message = 'closed'
                        WHERE idempotency_key = ? AND profile_key = ? AND operation_type = 'CLOSE'
                        """)) {
                    operation.setString(1, key);
                    operation.setString(2, profileKey);
                    operation.executeUpdate();
                }
                connection.commit();
            } catch (SQLException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    @Override
    public Optional<PendingClose> pendingClosure(String profileKey) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT idempotency_key, profile_key, destination_profile, actor, reason
                     FROM aurum_account_operations
                     WHERE profile_key = ? AND operation_type = 'CLOSE' AND operation_status = 'PENDING'
                     ORDER BY created_at LIMIT 1
                     """)) {
            statement.setString(1, normalizeKey(profileKey));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(pendingClose(result)) : Optional.empty();
            }
        }
    }

    @Override
    public List<PendingClose> pendingClosures(int limit) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT idempotency_key, profile_key, destination_profile, actor, reason
                     FROM aurum_account_operations
                     WHERE operation_type = 'CLOSE' AND operation_status = 'PENDING'
                     ORDER BY created_at LIMIT ?
                     """)) {
            statement.setInt(1, Math.clamp(limit, 1, 100));
            try (ResultSet result = statement.executeQuery()) {
                List<PendingClose> values = new ArrayList<>();
                while (result.next()) values.add(pendingClose(result));
                return List.copyOf(values);
            }
        }
    }

    private static PendingClose pendingClose(ResultSet result) throws SQLException {
        return new PendingClose(result.getString(1), result.getString(2), result.getString(3),
                result.getString(4), result.getString(5));
    }

    @Override
    public boolean hasUnresolvedHolds(List<AccountId> members) throws SQLException {
        if (members.isEmpty()) return false;
        String sourceConditions = String.join(" OR ", java.util.Collections.nCopies(members.size(),
                "(a.account_type = ? AND a.reference_id = ?)"));
        String targetConditions = String.join(" OR ", java.util.Collections.nCopies(members.size(),
                "(h.target_account_type = ? AND h.target_reference_id = ?)"));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1 FROM aurum_holds h JOIN aurum_accounts a ON a.id = h.account_id
                     WHERE h.status IN ('HELD', 'CAPTURING') AND ((
                     """ + sourceConditions + ") OR (" + targetConditions + ")) LIMIT 1")) {
            int index = 1;
            for (AccountId member : members) {
                statement.setString(index++, member.type().name());
                statement.setString(index++, member.reference());
            }
            for (AccountId member : members) {
                statement.setString(index++, member.type().name());
                statement.setString(index++, member.reference());
            }
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static Filter filter(ManagedAccountQuery query) {
        List<String> conditions = new ArrayList<>();
        List<String> values = new ArrayList<>();
        if (!query.includeTechnical()) conditions.add("p.technical = FALSE");
        if (!query.profileType().isEmpty()) { conditions.add("p.profile_type = ?"); values.add(query.profileType()); }
        if (query.status() != null) { conditions.add("p.status = ?"); values.add(query.status().name()); }
        if (!query.search().isEmpty()) {
            conditions.add("(p.profile_key LIKE ? OR p.display_name LIKE ? OR p.owner_id LIKE ? OR p.linked_object_id LIKE ?)");
            String like = "%" + query.search().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            for (int i = 0; i < 4; i++) values.add(like);
        }
        return new Filter(conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions), values);
    }

    private record Filter(String where, List<String> values) {
        int bind(PreparedStatement statement, int first) throws SQLException {
            int index = first;
            for (String value : values) statement.setString(index++, value);
            return index;
        }
    }

    private static String value(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? "" : value;
    }

    private static boolean profileExists(Connection connection, String key) throws SQLException {
        return lockStatus(connection, key) != null;
    }

    private static ManagedAccountStatus lockStatus(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM aurum_account_profiles WHERE profile_key = ? FOR UPDATE")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? ManagedAccountStatus.valueOf(result.getString(1)) : null;
            }
        }
    }

    private static WriteResult replay(Connection connection, String key, String hash, String type) throws SQLException {
        Operation operation = operation(connection, key);
        if (operation == null) return null;
        if (!operation.hash.equals(hash) || !operation.type.equals(type)) {
            return new WriteResult(WriteStatus.CONFLICT, "idempotency-key-reused");
        }
        WriteStatus status = operation.status.equals("COMPLETED") ? WriteStatus.DUPLICATE
                : operation.status.equals("REJECTED") ? WriteStatus.REJECTED : WriteStatus.SUCCESS;
        return new WriteResult(status, operation.message);
    }

    private static Operation operation(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT intent_hash, operation_type, operation_status, message
                FROM aurum_account_operations WHERE idempotency_key = ? FOR UPDATE
                """)) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new Operation(result.getString(1), result.getString(2),
                        result.getString(3), result.getString(4)) : null;
            }
        }
    }

    private record Operation(String hash, String type, String status, String message) {}

    private static void insertOperation(Connection connection, String key, String hash, String type,
                                        String profile, String destination, String status,
                                        String actor, String reason, String message) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO aurum_account_operations(idempotency_key, intent_hash, operation_type,
                    profile_key, destination_profile, operation_status, actor, reason, message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, key);
            statement.setString(2, hash);
            statement.setString(3, type);
            statement.setString(4, profile);
            statement.setString(5, destination);
            statement.setString(6, status);
            statement.setString(7, actor);
            statement.setString(8, reason);
            statement.setString(9, message);
            statement.executeUpdate();
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
    }

    private static String normalizeKey(String key) {
        key = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || key.length() > 191) throw new IllegalArgumentException("Invalid profile key");
        return key;
    }
}
