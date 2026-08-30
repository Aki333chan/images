package org.ChisaO_o.gladiatorArena;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/** MariaDB/SQLite-backed persistent player statistics. */
final class DatabaseManager implements AutoCloseable {
    record PlayerStats(int wins, int losses, int fights, int streak, int bestStreak,
                       double earnings, int betsWon, int betsLost) {
        static final PlayerStats EMPTY = new PlayerStats(0, 0, 0, 0, 0, 0.0, 0, 0);
    }

    private final GladiatorArena plugin;
    private final Map<UUID, PlayerStats> cache = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> revisions = new ConcurrentHashMap<>();
    private final Map<UUID, Object> writeLocks = new ConcurrentHashMap<>();
    private HikariDataSource dataSource;
    private volatile boolean ready;

    DatabaseManager(GladiatorArena plugin) {
        this.plugin = plugin;
    }

    boolean start() {
        close();
        try {
            FileConfiguration config = plugin.getConfig();
            String type = config.getString("database.type", "sqlite").trim().toLowerCase();
            HikariConfig hikari = new HikariConfig();
            hikari.setPoolName("GladiatorArena-DB");
            hikari.setConnectionTimeout(clamp(config.getLong("database.mariadb.connection_timeout_ms", 10_000L), 2_000L, 60_000L));
            hikari.setInitializationFailTimeout(-1L);
            if (type.equals("mariadb")) {
                String database = config.getString("database.mariadb.database", "gladiatorarena");
                if (!database.matches("[A-Za-z0-9_-]{1,64}")) {
                    throw new IllegalArgumentException("database.mariadb.database содержит недопустимые символы");
                }
                String host = config.getString("database.mariadb.host", "127.0.0.1");
                int port = Math.max(1, Math.min(65535, config.getInt("database.mariadb.port", 3306)));
                boolean ssl = config.getBoolean("database.mariadb.use_ssl", false);
                String customUrl = config.getString("database.mariadb.jdbc_url", "").trim();
                hikari.setDriverClassName("org.mariadb.jdbc.Driver");
                hikari.setJdbcUrl(customUrl.isEmpty() ? "jdbc:mariadb://" + host + ":" + port + "/" + database
                    + "?sslMode=" + (ssl ? "verify-full" : "disable") : customUrl);
                hikari.setUsername(config.getString("database.mariadb.username", "gladiatorarena"));
                hikari.setPassword(config.getString("database.mariadb.password", ""));
                hikari.setMaximumPoolSize(Math.max(1, Math.min(20, config.getInt("database.mariadb.pool_size", 5))));
                hikari.setMinimumIdle(1);
            } else if (type.equals("sqlite")) {
                String configured = config.getString("database.sqlite.file", "statistics.db");
                String safeName = new File(configured).getName();
                if (!safeName.toLowerCase().endsWith(".db")) safeName += ".db";
                File databaseFile = new File(plugin.getDataFolder(), safeName);
                hikari.setDriverClassName("org.sqlite.JDBC");
                hikari.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
                hikari.setMaximumPoolSize(1);
                hikari.setConnectionInitSql("PRAGMA busy_timeout=5000");
            } else {
                throw new IllegalArgumentException("database.type должен быть mariadb или sqlite");
            }
            dataSource = new HikariDataSource(hikari);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS gladiator_stats (
                      player_uuid VARCHAR(36) PRIMARY KEY,
                      player_name VARCHAR(32) NOT NULL,
                      wins INTEGER NOT NULL DEFAULT 0,
                      losses INTEGER NOT NULL DEFAULT 0,
                      fights INTEGER NOT NULL DEFAULT 0,
                      win_streak INTEGER NOT NULL DEFAULT 0,
                      best_streak INTEGER NOT NULL DEFAULT 0,
                      earnings DOUBLE NOT NULL DEFAULT 0,
                      bets_won INTEGER NOT NULL DEFAULT 0,
                      bets_lost INTEGER NOT NULL DEFAULT 0,
                      updated_at BIGINT NOT NULL
                    )
                    """);
            }
            ready = true;
            plugin.getLogger().info("Хранилище статистики подключено: " + type.toUpperCase());
            for (Player player : Bukkit.getOnlinePlayers()) load(player.getUniqueId());
            return true;
        } catch (Exception exception) {
            ready = false;
            plugin.getLogger().log(Level.SEVERE,
                "Не удалось подключить хранилище статистики. Игровые и финансовые операции не переключены на другую БД.", exception);
            close();
            return false;
        }
    }

    boolean isReady() {
        return ready;
    }

    PlayerStats get(UUID uuid) {
        return cache.getOrDefault(uuid, PlayerStats.EMPTY);
    }

    void load(UUID uuid) {
        if (!ready) return;
        async(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT wins,losses,fights,win_streak,best_streak,earnings,bets_won,bets_lost FROM gladiator_stats WHERE player_uuid=?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        cache.putIfAbsent(uuid, new PlayerStats(result.getInt(1), result.getInt(2), result.getInt(3), result.getInt(4),
                            result.getInt(5), result.getDouble(6), result.getInt(7), result.getInt(8)));
                    }
                }
            } catch (SQLException exception) {
                databaseError("чтении статистики", exception);
            }
        });
    }

    void recordMatch(UUID uuid, String name, boolean won, double earnings) {
        PlayerStats before = get(uuid);
        int streak = won ? before.streak + 1 : 0;
        PlayerStats after = new PlayerStats(before.wins + (won ? 1 : 0), before.losses + (won ? 0 : 1),
            before.fights + 1, streak, Math.max(before.bestStreak, streak), before.earnings + Math.max(0.0, earnings),
            before.betsWon, before.betsLost);
        cache.put(uuid, after);
        persist(uuid, name, after);
    }

    void recordBet(UUID uuid, String name, boolean won, double netEarnings) {
        PlayerStats before = get(uuid);
        PlayerStats after = new PlayerStats(before.wins, before.losses, before.fights, before.streak, before.bestStreak,
            before.earnings + Math.max(0.0, netEarnings), before.betsWon + (won ? 1 : 0), before.betsLost + (won ? 0 : 1));
        cache.put(uuid, after);
        persist(uuid, name, after);
    }

    private void persist(UUID uuid, String name, PlayerStats stats) {
        if (!ready) return;
        long revision = revisions.computeIfAbsent(uuid, ignored -> new AtomicLong()).incrementAndGet();
        async(() -> {
            synchronized (writeLocks.computeIfAbsent(uuid, ignored -> new Object())) {
                if (revisions.get(uuid).get() != revision) return;
                try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                int changed;
                try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE gladiator_stats SET player_name=?,wins=?,losses=?,fights=?,win_streak=?,best_streak=?,
                    earnings=?,bets_won=?,bets_lost=?,updated_at=? WHERE player_uuid=?
                    """)) {
                    bind(update, uuid, name, stats, false);
                    changed = update.executeUpdate();
                }
                if (changed == 0) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO gladiator_stats(player_uuid,player_name,wins,losses,fights,win_streak,best_streak,
                        earnings,bets_won,bets_lost,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                        """)) {
                        bind(insert, uuid, name, stats, true);
                        try {
                            insert.executeUpdate();
                        } catch (SQLException race) {
                            try (PreparedStatement update = connection.prepareStatement("""
                                UPDATE gladiator_stats SET player_name=?,wins=?,losses=?,fights=?,win_streak=?,best_streak=?,
                                earnings=?,bets_won=?,bets_lost=?,updated_at=? WHERE player_uuid=?
                                """)) {
                                bind(update, uuid, name, stats, false);
                                update.executeUpdate();
                            }
                        }
                    }
                }
                connection.commit();
                } catch (SQLException exception) {
                    databaseError("записи статистики", exception);
                }
            }
        });
    }

    private static void bind(PreparedStatement statement, UUID uuid, String name, PlayerStats stats, boolean insert) throws SQLException {
        int i = 1;
        if (insert) statement.setString(i++, uuid.toString());
        statement.setString(i++, name == null ? "Unknown" : name.substring(0, Math.min(32, name.length())));
        statement.setInt(i++, stats.wins);
        statement.setInt(i++, stats.losses);
        statement.setInt(i++, stats.fights);
        statement.setInt(i++, stats.streak);
        statement.setInt(i++, stats.bestStreak);
        statement.setDouble(i++, stats.earnings);
        statement.setInt(i++, stats.betsWon);
        statement.setInt(i++, stats.betsLost);
        statement.setLong(i++, System.currentTimeMillis());
        if (!insert) statement.setString(i, uuid.toString());
    }

    private void async(Runnable runnable) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
    }

    private void databaseError(String operation, SQLException exception) {
        plugin.getLogger().log(Level.WARNING, "Ошибка при " + operation + ": " + exception.getMessage(), exception);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void close() {
        ready = false;
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
