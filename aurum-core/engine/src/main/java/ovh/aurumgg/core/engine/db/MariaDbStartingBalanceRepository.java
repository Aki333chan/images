package ovh.aurumgg.core.engine.db;

import java.sql.*;
import java.util.UUID;
import javax.sql.DataSource;
import ovh.aurumgg.core.engine.*;

/** Durable first-seen decisions and compare-and-set configuration revisions. */
public final class MariaDbStartingBalanceRepository implements StartingBalanceRepository {
    private final DataSource source;
    public MariaDbStartingBalanceRepository(DataSource source) { this.source = source; }

    public void initialize(StartingBalanceSettings defaults, long now) throws SQLException {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                int inserted;
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT IGNORE INTO aurum_starting_balance_settings
                        (singleton, revision, enabled, currency_id, amount, installed_at)
                        VALUES (1, 1, ?, ?, ?, ?)
                        """)) {
                    s.setBoolean(1, defaults.enabled()); s.setString(2, defaults.currencyId());
                    s.setBigDecimal(3, defaults.amount()); s.setLong(4, now);
                    inserted = s.executeUpdate();
                }
                if (inserted == 1) {
                    history(c, new StartingBalanceSettings(1, defaults.enabled(), defaults.currencyId(), defaults.amount()),
                            "config", "initial configuration");
                    // Existing zero balances/profiles are old players too. Never reward reset player.dat files.
                    try (Statement s = c.createStatement()) {
                        s.executeUpdate("""
                            INSERT IGNORE INTO aurum_starting_balance_players
                            (player_uuid, currency_id, amount, settings_revision, decision_status)
                            SELECT reference_id, '', 0, 1, 'SKIPPED' FROM aurum_accounts WHERE account_type='PLAYER'
                            """);
                        s.executeUpdate("""
                            INSERT IGNORE INTO aurum_starting_balance_players
                            (player_uuid, currency_id, amount, settings_revision, decision_status)
                            SELECT reference_id, '', 0, 1, 'SKIPPED' FROM aurum_account_profile_members WHERE account_type='PLAYER'
                            """);
                    }
                }
                c.commit();
            } catch (SQLException error) { c.rollback(); throw error; }
        }
    }
    @Override public StartingBalanceSettings current() throws SQLException {
        try (Connection c = source.getConnection()) { return read(c, false); }
    }
    private static StartingBalanceSettings read(Connection c, boolean lock) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT revision, enabled, currency_id, amount FROM aurum_starting_balance_settings WHERE singleton=1"
                        + (lock ? " FOR UPDATE" : ""));
             ResultSet r = s.executeQuery()) {
            if (!r.next()) throw new SQLException("Starting balance settings are not initialized");
            return new StartingBalanceSettings(r.getLong(1), r.getBoolean(2), r.getString(3), r.getBigDecimal(4));
        }
    }
    @Override public long installedAt() throws SQLException {
        try (Connection c = source.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT installed_at FROM aurum_starting_balance_settings WHERE singleton=1");
             ResultSet r = s.executeQuery()) {
            if (!r.next()) throw new SQLException("Starting balance settings are not initialized");
            return r.getLong(1);
        }
    }
    @Override public StartingBalanceSettings save(StartingBalanceSettings value, long expected,
                                                   String actor, String reason) throws Exception {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                StartingBalanceSettings old = read(c, true);
                if (old.revision() != expected) throw new StaleRuleRevisionException(expected, old.revision());
                StartingBalanceSettings saved = new StartingBalanceSettings(expected + 1, value.enabled(), value.currencyId(), value.amount());
                try (PreparedStatement s = c.prepareStatement("""
                        UPDATE aurum_starting_balance_settings SET revision=?, enabled=?, currency_id=?, amount=? WHERE singleton=1
                        """)) {
                    s.setLong(1, saved.revision()); s.setBoolean(2, saved.enabled());
                    s.setString(3, saved.currencyId()); s.setBigDecimal(4, saved.amount()); s.executeUpdate();
                }
                history(c, saved, actor, reason);
                c.commit();
                return saved;
            } catch (Exception error) { c.rollback(); throw error; }
        }
    }
    private static void history(Connection c, StartingBalanceSettings value, String actor, String reason) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO aurum_starting_balance_revisions (revision, enabled, currency_id, amount, actor, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            s.setLong(1, value.revision()); s.setBoolean(2, value.enabled()); s.setString(3, value.currencyId());
            s.setBigDecimal(4, value.amount()); s.setString(5, actor); s.setString(6, reason); s.executeUpdate();
        }
    }
    @Override public Decision observe(UUID player, boolean newcomer) throws SQLException {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                StartingBalanceSettings settings = read(c, true);
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT IGNORE INTO aurum_starting_balance_players
                        (player_uuid, currency_id, amount, settings_revision, decision_status) VALUES (?, ?, ?, ?, ?)
                        """)) {
                    s.setString(1, player.toString()); s.setString(2, settings.currencyId());
                    s.setBigDecimal(3, settings.amount()); s.setLong(4, settings.revision());
                    s.setString(5, newcomer && settings.grants() ? "PENDING" : "SKIPPED"); s.executeUpdate();
                }
                Decision result;
                try (PreparedStatement s = c.prepareStatement("""
                        SELECT currency_id, amount, settings_revision, decision_status
                        FROM aurum_starting_balance_players WHERE player_uuid=?
                        """)) {
                    s.setString(1, player.toString());
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new SQLException("Starting balance decision missing");
                        result = new Decision(player, r.getString(1), r.getBigDecimal(2), r.getLong(3), r.getString(4));
                    }
                }
                c.commit();
                return result;
            } catch (SQLException error) { c.rollback(); throw error; }
        }
    }
    @Override public void finish(UUID player) throws SQLException {
        try (Connection c = source.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     UPDATE aurum_starting_balance_players SET decision_status='PAID'
                     WHERE player_uuid=? AND decision_status='PENDING'
                     """)) {
            s.setString(1, player.toString()); s.executeUpdate();
        }
    }
}
