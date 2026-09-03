package ovh.aurumgg.auth.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ovh.aurumgg.auth.api.IpRecord;

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
    /** Таблица токенов сброса — производная от основной, отдельной настройки не нужно. */
    private final String resetTable;
    /** Таблица истории входов — там же. */
    private final String historyTable;

    /** Адреса, с которых заходили. Пишется здесь, читается только панелью. */
    private final String ipTable;

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
        this.resetTable = config.tableName() + "_resets";
        this.historyTable = config.tableName() + "_logins";
        this.ipTable = config.tableName() + "_ips";
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
        // Токены сброса отдельной таблицей, а не колонками в аккаунте: у
        // аккаунта может не быть ни одного токена месяцами, а колонки под него
        // были бы в каждой строке. Плюс отдельная таблица чистится целиком,
        // не трогая аккаунты.
        //
        // token_hash уникален и проиндексирован — по нему идёт поиск. Самого
        // токена здесь нет нигде: см. пояснение в ResetTokens.
        String resetDdl = """
                CREATE TABLE IF NOT EXISTS %s (
                  token_hash  CHAR(64)  NOT NULL PRIMARY KEY,
                  uuid        CHAR(36)  NOT NULL,
                  issued_at   TIMESTAMP NOT NULL,
                  expires_at  TIMESTAMP NOT NULL,
                  used_at     TIMESTAMP NULL,
                  KEY idx_uuid (uuid),
                  KEY idx_expires (expires_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
                """.formatted(resetTable);

        // История входов. Отдельной таблицей и без внешнего ключа на аккаунт:
        // она переживает удаление аккаунта намеренно — история про то, что
        // происходило, и удаление регистрации этого не отменяет. Ник хранится
        // строкой по той же причине.
        String historyDdl = """
                CREATE TABLE IF NOT EXISTS %s (
                  id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  uuid       CHAR(36)    NOT NULL,
                  username   VARCHAR(16) NOT NULL,
                  at         TIMESTAMP   NOT NULL,
                  ip         VARCHAR(45) NULL,
                  result     VARCHAR(16) NOT NULL,
                  server_id  VARCHAR(64) NULL,
                  KEY idx_username_at (username, at),
                  KEY idx_at (at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
                """.formatted(historyTable);

        // Адреса игрока. Одна строка на пару (uuid, ip) — не журнал заходов,
        // а «когда впервые и когда в последний раз». Журнал входов уже есть
        // отдельно и отвечает на другой вопрос; тысячи строк про один и тот же
        // домашний адрес не сказали бы ничего сверх этих двух дат.
        //
        // Составной первичный ключ вместо суррогатного id: он же и есть
        // естественный ключ, и он же нужен для ON DUPLICATE KEY UPDATE.
        String ipDdl = """
                CREATE TABLE IF NOT EXISTS %s (
                  uuid       CHAR(36)    NOT NULL,
                  ip         VARCHAR(45) NOT NULL,
                  first_seen TIMESTAMP   NOT NULL,
                  last_seen  TIMESTAMP   NOT NULL,
                  PRIMARY KEY (uuid, ip),
                  KEY idx_last_seen (uuid, last_seen)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
                """.formatted(ipTable);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
            statement.executeUpdate(resetDdl);
            statement.executeUpdate(historyDdl);
            statement.executeUpdate(ipDdl);

            // ДОБАВЛЕНИЕ КОЛОНОК В УЖЕ СУЩЕСТВУЮЩУЮ ТАБЛИЦУ. CREATE TABLE IF
            // NOT EXISTS выше ничего не сделает там, где таблица заведена
            // прошлой версией плагина, — а колонок двухфакторки в ней нет.
            // ADD COLUMN IF NOT EXISTS есть в MariaDB с 10.0.2, и это ровно
            // тот случай, ради которого он существует.
            for (String column : new String[] {
                "totp_secret VARCHAR(64) NULL",
                "totp_enabled TINYINT(1) NOT NULL DEFAULT 0",
                "totp_last_counter BIGINT NULL",
            }) {
                statement.executeUpdate(
                        "ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column);
            }
        }
    }

    // ---------------------------------------------- удаление регистрации

    @Override
    public boolean deleteAccount(UUID uuid) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement tokens = connection.prepareStatement(
                            "DELETE FROM " + resetTable + " WHERE uuid = ?");
                    PreparedStatement account = connection.prepareStatement(
                            "DELETE FROM " + table + " WHERE uuid = ?")) {
                tokens.setString(1, uuid.toString());
                tokens.executeUpdate();
                account.setString(1, uuid.toString());
                boolean removed = account.executeUpdate() > 0;
                connection.commit();
                return removed;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    // -------------------------------------------------- история входов

    @Override
    public void recordLogin(UUID uuid, String username, LoginRecord record) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + historyTable
                                + " (uuid, username, at, ip, result, server_id) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, username);
            statement.setTimestamp(3, Timestamp.from(record.at()));
            statement.setString(4, record.ip());
            statement.setString(5, record.result().name());
            statement.setString(6, record.serverId());
            statement.executeUpdate();
        }
    }

    @Override
    public List<LoginRecord> loginHistory(String username, Instant since, int limit) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT at, ip, result, server_id FROM " + historyTable
                                + " WHERE LOWER(username) = LOWER(?) AND at >= ? ORDER BY at DESC LIMIT ?")) {
            statement.setString(1, username);
            statement.setTimestamp(2, Timestamp.from(since));
            statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<LoginRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new LoginRecord(
                            rs.getTimestamp("at").toInstant(),
                            rs.getString("ip"),
                            parseResult(rs.getString("result")),
                            rs.getString("server_id")));
                }
                return result;
            }
        }
    }

    /**
     * Неизвестное значение в колонке — не повод падать.
     *
     * Строка, а не enum в схеме, выбрана намеренно: добавление нового исхода
     * не должно требовать ALTER TABLE на живой базе. Обратная сторона — сюда
     * может приехать значение от более новой версии плагина, и показать его
     * как «неизвестно» честнее, чем уронить всю историю.
     */
    private static LoginRecord.Result parseResult(String raw) {
        try {
            return LoginRecord.Result.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return LoginRecord.Result.WRONG_PASSWORD;
        }
    }

    @Override
    public int purgeLoginHistory(Instant before) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + historyTable + " WHERE at < ?")) {
            statement.setTimestamp(1, Timestamp.from(before));
            return statement.executeUpdate();
        }
    }

    // ----------------------------------------------------- двухфакторка

    @Override
    public void setTotp(UUID uuid, String secretBase32, boolean enabled) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + table
                                + " SET totp_secret = ?, totp_enabled = ?, totp_last_counter = NULL WHERE uuid = ?")) {
            statement.setString(1, secretBase32);
            statement.setBoolean(2, enabled);
            statement.setString(3, uuid.toString());
            statement.executeUpdate();
        }
    }

    @Override
    public void setTotpCounter(UUID uuid, long counter) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + table + " SET totp_last_counter = ? WHERE uuid = ?")) {
            statement.setLong(1, counter);
            statement.setString(2, uuid.toString());
            statement.executeUpdate();
        }
    }

    // ---------------------------------------------------------- сброс пароля

    @Override
    public void createResetToken(UUID uuid, String tokenHash, Instant issuedAt, Instant expiresAt)
            throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            // Прежние токены этого игрока гасим в той же транзакции: иначе
            // между удалением и вставкой существовал бы момент, когда у
            // игрока нет ни одного токена, а при сбое — момент, когда старый
            // остался живым рядом с новым.
            connection.setAutoCommit(false);
            try (PreparedStatement drop = connection.prepareStatement(
                            "DELETE FROM " + resetTable + " WHERE uuid = ?");
                    PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO " + resetTable
                                    + " (token_hash, uuid, issued_at, expires_at) VALUES (?, ?, ?, ?)")) {
                drop.setString(1, uuid.toString());
                drop.executeUpdate();
                insert.setString(1, tokenHash);
                insert.setString(2, uuid.toString());
                insert.setTimestamp(3, Timestamp.from(issuedAt));
                insert.setTimestamp(4, Timestamp.from(expiresAt));
                insert.executeUpdate();
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * Гашение токена.
     *
     * Атомарность даёт сам UPDATE: снять used_at с NULL может ровно один
     * запрос, остальные увидят 0 изменённых строк. Поэтому последующий SELECT
     * безопасен — строка уже наша, и никто другой её не заберёт.
     */
    @Override
    public Optional<UUID> consumeResetToken(String tokenHash, Instant now) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement claim = connection.prepareStatement(
                    "UPDATE " + resetTable + " SET used_at = ? "
                            + "WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?")) {
                claim.setTimestamp(1, Timestamp.from(now));
                claim.setString(2, tokenHash);
                claim.setTimestamp(3, Timestamp.from(now));
                if (claim.executeUpdate() == 0) return Optional.empty();
            }
            try (PreparedStatement owner = connection.prepareStatement(
                    "SELECT uuid FROM " + resetTable + " WHERE token_hash = ?")) {
                owner.setString(1, tokenHash);
                try (ResultSet rs = owner.executeQuery()) {
                    return rs.next() ? Optional.of(UUID.fromString(rs.getString("uuid"))) : Optional.empty();
                }
            }
        }
    }

    @Override
    public int purgeResetTokens(Instant now) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + resetTable + " WHERE expires_at < ? OR used_at IS NOT NULL")) {
            statement.setTimestamp(1, Timestamp.from(now));
            return statement.executeUpdate();
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

    /**
     * Отметить удачный вход.
     *
     * Здесь же дописывается история адресов — намеренно, а не отдельным
     * вызовом у каждого, кто зовёт этот метод. Зовут его из пяти мест
     * {@link AuthService} (обычный вход, вход по сессии, после смены пароля,
     * после двухфакторки, байпас), и требовать от каждого не забыть второй
     * вызов значит однажды получить дыру в истории ровно на одном из путей.
     *
     * Адреса может не быть — тогда записывать нечего.
     */
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
        if (ip != null && !ip.isBlank()) rememberIp(uuid, ip, at);
    }

    /**
     * Запомнить адрес: новый — завести, знакомый — подвинуть last_seen.
     *
     * Одним запросом через ON DUPLICATE KEY UPDATE, а не «сначала посмотреть,
     * потом вставить или обновить»: между двумя запросами успевает вклиниться
     * второй вход того же игрока, и вставка упала бы на первичном ключе.
     *
     * first_seen при повторном появлении НЕ трогается — в этом весь смысл
     * поля: оно отвечает, когда адрес увидели впервые.
     */
    private void rememberIp(UUID uuid, String ip, Instant at) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + ipTable + " (uuid, ip, first_seen, last_seen)"
                                + " VALUES (?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE last_seen = VALUES(last_seen)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, ip);
            statement.setTimestamp(3, Timestamp.from(at));
            statement.setTimestamp(4, Timestamp.from(at));
            statement.executeUpdate();
        }
    }

    @Override
    public List<IpRecord> ipHistory(UUID uuid) throws Exception {
        List<IpRecord> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT ip, first_seen, last_seen FROM " + ipTable
                                + " WHERE uuid = ? ORDER BY last_seen DESC")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new IpRecord(
                            rs.getString("ip"),
                            rs.getTimestamp("first_seen").toInstant(),
                            rs.getTimestamp("last_seen").toInstant()));
                }
            }
        }
        return result;
    }

    /**
     * Ники всех зарегистрированных, в нижнем регистре.
     *
     * Один запрос вместо проверки каждого ника по отдельности: панель делит
     * исторический список из сотен имён, и сотня обращений к базе на открытие
     * вкладки — не то, за что стоит платить.
     */
    @Override
    public Set<String> allUsernames() throws Exception {
        Set<String> result = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT username FROM " + table);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) result.add(rs.getString("username").toLowerCase(Locale.ROOT));
        }
        return result;
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
            // wasNull() относится к ПОСЛЕДНЕМУ прочитанному столбцу, поэтому
            // его надо спросить сразу — после getBoolean ниже он отвечал бы
            // уже про totp_enabled.
            long counter = rs.getLong("totp_last_counter");
            Long lastCounter = rs.wasNull() ? null : counter;
            return Optional.of(new AuthAccount(
                    UUID.fromString(rs.getString("uuid")),
                    rs.getString("username"),
                    rs.getString("password_hash"),
                    rs.getString("email"),
                    rs.getTimestamp("registered_at").toInstant(),
                    lastLogin == null ? null : lastLogin.toInstant(),
                    rs.getString("last_ip"),
                    rs.getString("totp_secret"),
                    rs.getBoolean("totp_enabled"),
                    lastCounter));
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
