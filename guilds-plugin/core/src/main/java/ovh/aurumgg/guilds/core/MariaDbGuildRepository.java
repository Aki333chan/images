package ovh.aurumgg.guilds.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ovh.aurumgg.guilds.api.BankAccess;
import ovh.aurumgg.guilds.api.GuildBankEntry;
import ovh.aurumgg.guilds.api.GuildMember;
import ovh.aurumgg.guilds.api.GuildRank;
import ovh.aurumgg.guilds.api.GuildSettings;
import ovh.aurumgg.guilds.api.JoinPolicy;

/**
 * Хранилище на MariaDB с пулом соединений.
 *
 * <h2>Три таблицы и почему именно так</h2>
 *
 * <b>Гильдии</b> и <b>участники</b> разделены, потому что состав меняется куда
 * чаще самой гильдии, а список из пятидесяти UUID в одной колонке — это
 * невозможность спросить «в какой гильдии вот этот игрок» иначе, чем перебором
 * всех строк.
 *
 * <b>Лог банка</b> отдельно и без внешнего ключа на гильдию — намеренно. Он
 * переживает роспуск гильдии, потому что вопрос «куда делись деньги из общака»
 * возникает как раз тогда, когда гильдии уже нет.
 *
 * <h2>Один сервер на одну базу</h2>
 *
 * В отличие от базы авторизации, эту НЕ стоит делить между несколькими
 * серверами сети. Гильдии живут в памяти плагина (иначе HUD и чат упирались бы
 * в базу), а механизма оповещения соседнего сервера об изменении здесь нет:
 * второй сервер продолжил бы показывать состав, который уже поменялся.
 * Сквозные гильдии на всю сеть — это отдельная задача с шиной сообщений, и
 * делать вид, что она решена, было бы хуже, чем честно это написать.
 *
 * <h2>UUID строкой</h2>
 *
 * Как и в базе авторизации: CHAR(36) вместо BINARY(16). Таблицу можно читать и
 * править руками, а выигрыш в двадцать байт на строку при масштабе игрового
 * сервера не значит ничего.
 */
public final class MariaDbGuildRepository implements GuildRepository {

    private final HikariDataSource dataSource;
    private final String guilds;
    private final String members;
    private final String bankLog;

    public MariaDbGuildRepository(GuildsConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.dbUsername());
        hikari.setPassword(config.dbPassword());
        hikari.setMaximumPoolSize(config.poolSize());
        hikari.setPoolName("AurumGuilds");
        hikari.setConnectionTimeout(5_000);
        // Соединения, простоявшие дольше получаса, MariaDB закрывает сама
        // (wait_timeout). Отпускаем их раньше, иначе первый запрос после
        // затишья приходит в мёртвое соединение.
        hikari.setMaxLifetime(15 * 60_000L);
        this.dataSource = new HikariDataSource(hikari);
        this.guilds = config.guildsTable();
        this.members = config.membersTable();
        this.bankLog = config.bankLogTable();
    }

    @Override
    public void initSchema() throws Exception {
        // Уникальные ключи по имени и тегу — на уровне БД, а не только в коде.
        // Проверка «занято ли имя» и вставка идут двумя запросами, между
        // которыми успевает вклиниться второй игрок; без ключа в базе так
        // появились бы две гильдии с одним именем, и разошлись бы они молча.
        //
        // Сортировка utf8mb4_general_ci сравнивает без учёта регистра — это и
        // даёт «Драконы» и «драконы» как одно занятое имя.
        String guildsDdl = """
                CREATE TABLE IF NOT EXISTS %s (
                  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  name          VARCHAR(32)  NOT NULL,
                  tag           VARCHAR(16)  NOT NULL,
                  leader_uuid   CHAR(36)     NOT NULL,
                  bank_balance  DECIMAL(20,2) NOT NULL DEFAULT 0,
                  friendly_fire TINYINT(1)   NOT NULL DEFAULT 0,
                  join_policy   VARCHAR(16)  NOT NULL DEFAULT 'invite',
                  motd          VARCHAR(190) NOT NULL DEFAULT '',
                  bank_access   VARCHAR(24)  NOT NULL DEFAULT 'leader_only',
                  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_name (name),
                  UNIQUE KEY uk_tag (tag)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
                """.formatted(guilds);

        // Первичный ключ по uuid, а не по паре (guild_id, uuid): игрок состоит
        // максимум в одной гильдии, и база должна это гарантировать сама.
        // Иначе ошибка в коде дала бы человека сразу в двух гильдиях, и
        // выяснилось бы это по двум суффиксам у его ника.
        //
        // ON DELETE CASCADE: роспуск гильдии обязан уносить её состав. Без
        // каскада забытая строка означала бы игрока, который «состоит» в
        // несуществующей гильдии и не может вступить ни в какую другую.
        String membersDdl = """
                CREATE TABLE IF NOT EXISTS %s (
                  uuid      CHAR(36)    NOT NULL PRIMARY KEY,
                  guild_id  BIGINT      NOT NULL,
                  username  VARCHAR(16) NOT NULL,
                  rank_name VARCHAR(16) NOT NULL,
                  joined_at TIMESTAMP   NOT NULL,
                  KEY idx_guild (guild_id),
                  CONSTRAINT fk_member_guild FOREIGN KEY (guild_id)
                      REFERENCES %s (id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
                """.formatted(members, guilds);

        // Ни внешнего ключа, ни каскада: лог переживает и уход участника, и
        // роспуск гильдии. Ник хранится строкой по той же причине — он про то,
        // как человека звали в момент операции.
        String bankDdl = """
                CREATE TABLE IF NOT EXISTS %s (
                  id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  guild_id      BIGINT        NOT NULL,
                  actor_uuid    CHAR(36)      NOT NULL,
                  actor_name    VARCHAR(16)   NOT NULL,
                  deposit       TINYINT(1)    NOT NULL,
                  amount        DECIMAL(20,2) NOT NULL,
                  balance_after DECIMAL(20,2) NOT NULL,
                  at            TIMESTAMP     NOT NULL,
                  KEY idx_guild_at (guild_id, at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
                """.formatted(bankLog);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(guildsDdl);
            statement.executeUpdate(membersDdl);
            statement.executeUpdate(bankDdl);
        }
    }

    @Override
    public List<StoredGuild> loadAll() throws Exception {
        Map<Long, List<GuildMember>> byGuild = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT guild_id, uuid, username, rank_name, joined_at FROM " + members);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                byGuild.computeIfAbsent(rs.getLong("guild_id"), key -> new ArrayList<>())
                        .add(new GuildMember(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("username"),
                                GuildRank.parse(rs.getString("rank_name")),
                                rs.getTimestamp("joined_at").toInstant()));
            }
        }

        List<StoredGuild> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, name, tag, leader_uuid, bank_balance, friendly_fire, "
                                + "join_policy, motd, bank_access, created_at FROM " + guilds);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("id");
                result.add(new StoredGuild(
                        id,
                        rs.getString("name"),
                        rs.getString("tag"),
                        UUID.fromString(rs.getString("leader_uuid")),
                        rs.getBigDecimal("bank_balance").doubleValue(),
                        rs.getTimestamp("created_at").toInstant(),
                        new GuildSettings(
                                rs.getBoolean("friendly_fire"),
                                JoinPolicy.parse(rs.getString("join_policy")),
                                rs.getString("motd"),
                                BankAccess.parse(rs.getString("bank_access"))),
                        byGuild.getOrDefault(id, List.of())));
            }
        }
        return result;
    }

    @Override
    public long createGuild(
            String name, String tag, UUID leader, String leaderName, Instant createdAt,
            GuildSettings settings) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            // Гильдия и её лидер появляются одной транзакцией: гильдия без
            // лидера — это состояние, из которого нет выхода ни одной командой.
            connection.setAutoCommit(false);
            try {
                long id;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + guilds + " (name, tag, leader_uuid, bank_balance, "
                                + "friendly_fire, join_policy, motd, bank_access, created_at) "
                                + "VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, name);
                    statement.setString(2, tag);
                    statement.setString(3, leader.toString());
                    statement.setBoolean(4, settings.friendlyFire());
                    statement.setString(5, settings.joinPolicy().storageName());
                    statement.setString(6, settings.motd());
                    statement.setString(7, settings.bankAccess().storageName());
                    statement.setTimestamp(8, Timestamp.from(createdAt));
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new IllegalStateException("База не вернула ключ гильдии");
                        id = keys.getLong(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + members + " (uuid, guild_id, username, rank_name, joined_at) "
                                + "VALUES (?, ?, ?, ?, ?)")) {
                    statement.setString(1, leader.toString());
                    statement.setLong(2, id);
                    statement.setString(3, leaderName);
                    statement.setString(4, GuildRank.LEADER.storageName());
                    statement.setTimestamp(5, Timestamp.from(createdAt));
                    statement.executeUpdate();
                }
                connection.commit();
                return id;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    @Override
    public void deleteGuild(long guildId) throws Exception {
        // Участников уносит ON DELETE CASCADE — отдельный DELETE здесь был бы
        // вторым местом, где записано одно и то же правило.
        update("DELETE FROM " + guilds + " WHERE id = ?", statement -> statement.setLong(1, guildId));
    }

    @Override
    public void updateTag(long guildId, String tag) throws Exception {
        update("UPDATE " + guilds + " SET tag = ? WHERE id = ?", statement -> {
            statement.setString(1, tag);
            statement.setLong(2, guildId);
        });
    }

    @Override
    public void updateSettings(long guildId, GuildSettings settings) throws Exception {
        update("UPDATE " + guilds + " SET friendly_fire = ?, join_policy = ?, motd = ?, "
                + "bank_access = ? WHERE id = ?", statement -> {
            statement.setBoolean(1, settings.friendlyFire());
            statement.setString(2, settings.joinPolicy().storageName());
            statement.setString(3, settings.motd());
            statement.setString(4, settings.bankAccess().storageName());
            statement.setLong(5, guildId);
        });
    }

    @Override
    public void updateLeader(long guildId, UUID leader) throws Exception {
        update("UPDATE " + guilds + " SET leader_uuid = ? WHERE id = ?", statement -> {
            statement.setString(1, leader.toString());
            statement.setLong(2, guildId);
        });
    }

    @Override
    public void updateBank(long guildId, double balance) throws Exception {
        update("UPDATE " + guilds + " SET bank_balance = ? WHERE id = ?", statement -> {
            statement.setBigDecimal(1, java.math.BigDecimal.valueOf(balance));
            statement.setLong(2, guildId);
        });
    }

    @Override
    public void addMember(long guildId, UUID uuid, String username, GuildRank rank, Instant joinedAt)
            throws Exception {
        // ON DUPLICATE KEY UPDATE, а не INSERT: строка участника могла остаться
        // от прошлой гильдии, если её удаляли в обход каскада. Перезапись
        // безопаснее отказа — игрок в любом случае состоит ровно в одной.
        update("INSERT INTO " + members + " (uuid, guild_id, username, rank_name, joined_at) "
                + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                + "guild_id = VALUES(guild_id), username = VALUES(username), "
                + "rank_name = VALUES(rank_name), joined_at = VALUES(joined_at)", statement -> {
            statement.setString(1, uuid.toString());
            statement.setLong(2, guildId);
            statement.setString(3, username);
            statement.setString(4, rank.storageName());
            statement.setTimestamp(5, Timestamp.from(joinedAt));
        });
    }

    @Override
    public void removeMember(long guildId, UUID uuid) throws Exception {
        // guild_id в условии, хотя ключ — uuid: если игрок уже успел оказаться
        // в другой гильдии, запоздавший запрос не должен выкинуть его оттуда.
        update("DELETE FROM " + members + " WHERE uuid = ? AND guild_id = ?", statement -> {
            statement.setString(1, uuid.toString());
            statement.setLong(2, guildId);
        });
    }

    @Override
    public void updateRank(long guildId, UUID uuid, GuildRank rank) throws Exception {
        update("UPDATE " + members + " SET rank_name = ? WHERE uuid = ? AND guild_id = ?",
                statement -> {
                    statement.setString(1, rank.storageName());
                    statement.setString(2, uuid.toString());
                    statement.setLong(3, guildId);
                });
    }

    @Override
    public void updateUsername(UUID uuid, String username) throws Exception {
        update("UPDATE " + members + " SET username = ? WHERE uuid = ?", statement -> {
            statement.setString(1, username);
            statement.setString(2, uuid.toString());
        });
    }

    @Override
    public void logBank(GuildBankEntry entry) throws Exception {
        update("INSERT INTO " + bankLog + " (guild_id, actor_uuid, actor_name, deposit, amount, "
                + "balance_after, at) VALUES (?, ?, ?, ?, ?, ?, ?)", statement -> {
            statement.setLong(1, entry.guildId());
            statement.setString(2, entry.actorUuid().toString());
            statement.setString(3, entry.actorName());
            statement.setBoolean(4, entry.deposit());
            statement.setBigDecimal(5, java.math.BigDecimal.valueOf(entry.amount()));
            statement.setBigDecimal(6, java.math.BigDecimal.valueOf(entry.balanceAfter()));
            statement.setTimestamp(7, Timestamp.from(entry.at()));
        });
    }

    @Override
    public List<GuildBankEntry> bankHistory(long guildId, int limit) throws Exception {
        List<GuildBankEntry> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT actor_uuid, actor_name, deposit, amount, balance_after, at FROM "
                                + bankLog + " WHERE guild_id = ? ORDER BY at DESC, id DESC LIMIT ?")) {
            statement.setLong(1, guildId);
            statement.setInt(2, Math.max(1, Math.min(500, limit)));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new GuildBankEntry(
                            rs.getTimestamp("at").toInstant(),
                            guildId,
                            UUID.fromString(rs.getString("actor_uuid")),
                            rs.getString("actor_name"),
                            rs.getBoolean("deposit"),
                            rs.getBigDecimal("amount").doubleValue(),
                            rs.getBigDecimal("balance_after").doubleValue()));
                }
            }
        }
        return result;
    }

    @Override
    public void close() {
        dataSource.close();
    }

    // --------------------------------------------------------- внутреннее

    /** Подстановка параметров — чтобы каждый update не тащил свой try-with-resources. */
    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws Exception;
    }

    private void update(String sql, Binder binder) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        }
    }
}
