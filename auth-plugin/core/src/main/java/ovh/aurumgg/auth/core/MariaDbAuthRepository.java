package ovh.aurumgg.auth.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Хранилище на MariaDB с пулом соединений.
 *
 * ПРО ПУЛ. Открывать соединение на каждый запрос — это TCP-рукопожатие плюс
 * аутентификация MariaDB на каждый вход игрока; при заходе десятка человек
 * подряд это заметно даже асинхронно, а при недоступной БД превращается в
 * очередь висящих коннектов. HikariCP держит несколько готовых соединений и
 * переиспользует их.
 *
 * ПРО ОБЩУЮ БАЗУ НА ВСЕ СЕРВЕРЫ. Таблица намеренно не привязана к конкретному
 * серверу: одна база на все сервера сети даёт сквозной вход — зарегистрировался
 * на выживании, зашёл на креатив тем же паролем. Ключ — UUID, он одинаков
 * везде.
 */
public final class MariaDbAuthRepository implements AuthRepository {

    private final HikariDataSource dataSource;
    private final String table;

    public MariaDbAuthRepository(AuthConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.dbUsername());
        hikari.setPassword(config.dbPassword());
        hikari.setMaximumPoolSize(config.poolSize());
        hikari.setPoolName("AurumAuth");
        // Заход игрока не должен ждать соединения дольше нескольких секунд:
        // лучше честно отказать и попросить зайти снова, чем держать человека
        // в подвешенном состоянии.
        hikari.setConnectionTimeout(5_000);
        // Соединения, простоявшие дольше получаса, MariaDB закрывает сама
        // (wait_timeout). Отпускаем их раньше, чем это сделает сервер, иначе
        // первый запрос после затишья приходит в мёртвое соединение.
        hikari.setMaxLifetime(15 * 60_000L);
        this.dataSource = new HikariDataSource(hikari);
        this.table = config.tableName();
    }

    /**
     * Схема.
     *
     * uuid хранится строкой в каноничном виде с дефисами (36 символов), а не
     * BINARY(16): так таблицу можно читать и править руками, а выигрыш в 20
     * байт на строку при масштабе игрового сервера не значит ничего.
     *
     * username отдельно от uuid и с уникальным индексом: ник — то, что человек
     * вводит, и два аккаунта на один ник (лицензионный и пиратский UUID)
     * сделали бы вход неоднозначным.
     *
     * email заведён сразу и допускает null. Сейчас он не используется нигде —
     * это задел под сброс пароля письмом. Добавлять колонку в живую таблицу
     * потом дороже, чем завести её пустой сейчас.
     */
    @Override
    public void initSchema() throws Exception {
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                  uuid           CHAR(36)     NOT NULL PRIMARY KEY,
                  username       VARCHAR(16)  NOT NULL,
                  password_hash  VARCHAR(120) NOT NULL,
                  email          VARCHAR(190) NULL,
                  registered_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  last_login_at  TIMESTAMP    NULL,
                  last_ip        VARCHAR(45)  NULL,
                  UNIQUE KEY uk_username (username)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
                """.formatted(table);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        }
    }

    @Override
    public Optional<AuthAccount> findByUuid(UUID uuid) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM " + table + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            return readOne(statement);
        }
    }

    /**
     * Регистр ника игнорируется на уровне сравнения, а не колонки: collation
     * utf8mb4_general_ci и так регистронезависима, но полагаться на настройку
     * таблицы, которую кто-то однажды пересоздаст иначе, не стоит.
     */
    @Override
    public Optional<AuthAccount> findByUsername(String username) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM " + table + " WHERE LOWER(username) = LOWER(?)")) {
            statement.setString(1, username);
            return readOne(statement);
        }
    }

    @Override
    public void create(AuthAccount account) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + table
                                + " (uuid, username, password_hash, email, registered_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, account.uuid().toString());
            statement.setString(2, account.username());
            statement.setString(3, account.passwordHash());
            statement.setString(4, account.email());
            statement.setTimestamp(5, Timestamp.from(account.registeredAt()));
            statement.executeUpdate();
        }
    }

    @Override
    public void touchLogin(UUID uuid, Instant at, String ip) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + table + " SET last_login_at = ?, last_ip = ? WHERE uuid = ?")) {
            statement.setTimestamp(1, Timestamp.from(at));
            statement.setString(2, ip);
            statement.setString(3, uuid.toString());
            statement.executeUpdate();
        }
    }

    @Override
    public void updatePasswordHash(UUID uuid, String passwordHash) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + table + " SET password_hash = ? WHERE uuid = ?")) {
            statement.setString(1, passwordHash);
            statement.setString(2, uuid.toString());
            statement.executeUpdate();
        }
    }

    private static Optional<AuthAccount> readOne(PreparedStatement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            Timestamp lastLogin = rs.getTimestamp("last_login_at");
            return Optional.of(new AuthAccount(
                    UUID.fromString(rs.getString("uuid")),
                    rs.getString("username"),
                    rs.getString("password_hash"),
                    rs.getString("email"),
                    rs.getTimestamp("registered_at").toInstant(),
                    lastLogin == null ? null : lastLogin.toInstant(),
                    rs.getString("last_ip")));
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
