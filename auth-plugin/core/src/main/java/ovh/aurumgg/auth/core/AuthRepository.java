package ovh.aurumgg.auth.core;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import ovh.aurumgg.auth.api.IpRecord;

/**
 * Хранилище аккаунтов.
 *
 * ВСЕ МЕТОДЫ БЛОКИРУЮЩИЕ и вызываются только из асинхронного контекста — за
 * этим следит AuthService, который единственный их и дёргает. Интерфейс
 * намеренно не возвращает CompletableFuture: смешивать «куда сходить» и «в
 * каком потоке» в одном месте — верный способ однажды сходить в БД с главного
 * потока и не заметить этого до первого лага.
 */
public interface AuthRepository extends AutoCloseable {

    /** Создать таблицу, если её ещё нет. */
    void initSchema() throws Exception;

    Optional<AuthAccount> findByUuid(UUID uuid) throws Exception;

    /**
     * Поиск по нику без учёта регистра.
     *
     * Нужен отдельно от поиска по UUID: на смешанном сервере один и тот же ник
     * даёт разные UUID у лицензионного и пиратского клиента, и «занят ли ник»
     * — это вопрос именно про ник.
     */
    Optional<AuthAccount> findByUsername(String username) throws Exception;

    /** Завести аккаунт. Бросает исключение, если ник или UUID уже заняты. */
    void create(AuthAccount account) throws Exception;

    /** Отметить успешный вход: время и адрес. */
    void touchLogin(UUID uuid, Instant at, String ip) throws Exception;

    /** Сменить пароль. */
    void updatePasswordHash(UUID uuid, String passwordHash) throws Exception;

    // ------------------------------------------------------ сброс пароля

    /**
     * Завести токен сброса, погасив прежние токены этого игрока.
     *
     * Гашение обязательно: два живых токена на один аккаунт — это просто
     * лишний шанс, что сработает тот, который подсмотрели.
     */
    void createResetToken(UUID uuid, String tokenHash, Instant issuedAt, Instant expiresAt)
            throws Exception;

    /**
     * Погасить токен и вернуть UUID, которому он принадлежал.
     *
     * Операция обязана быть АТОМАРНОЙ — «пометить использованным» и «вернуть
     * владельца» одним запросом. Иначе два одновременных ввода одного токена
     * оба прошли бы проверку, и второй сбросил бы пароль поверх первого.
     *
     * Пусто, если токена нет, он уже использован или истёк.
     */
    Optional<UUID> consumeResetToken(String tokenHash, Instant now) throws Exception;

    /** Убрать использованные и истёкшие токены. */
    int purgeResetTokens(Instant now) throws Exception;

    // ------------------------------------------------ удаление регистрации

    /**
     * Удалить аккаунт целиком.
     *
     * Вместе с ним уходят его токены сброса: оставленный токен от удалённого
     * аккаунта — мусор, который однажды сработает не на том, кого ждали.
     * История входов, наоборот, ОСТАЁТСЯ: она про то, что происходило, и
     * удаление аккаунта этого не отменяет.
     *
     * @return false, если такого аккаунта не было
     */
    boolean deleteAccount(UUID uuid) throws Exception;

    // ---------------------------------------------------- история входов

    /** Записать попытку входа — и удачную, и нет. */
    void recordLogin(UUID uuid, String username, LoginRecord record) throws Exception;

    /** Последние попытки по нику за период, новые сверху. */
    List<LoginRecord> loginHistory(String username, Instant since, int limit) throws Exception;

    /** Убрать записи старше срока хранения. */
    int purgeLoginHistory(Instant before) throws Exception;

    /**
     * Адреса, с которых заходил игрок, — новые сверху.
     *
     * Записываются внутри {@link #touchLogin}, отдельного метода для записи
     * нет намеренно: тогда её пришлось бы не забыть в пяти местах, откуда
     * touchLogin зовут.
     */
    List<IpRecord> ipHistory(UUID uuid) throws Exception;

    /** Ники всех зарегистрированных, в нижнем регистре. Одним запросом. */
    Set<String> allUsernames() throws Exception;

    // ------------------------------------------------------- двухфакторка

    /**
     * Записать секрет и состояние двухфакторки.
     *
     * Секрет и флаг вместе: они меняются только парой — «завели, но не
     * подтвердили» и «подтвердили» отличаются ровно флагом.
     */
    void setTotp(UUID uuid, String secretBase32, boolean enabled) throws Exception;

    /** Запомнить принятый интервал, чтобы тот же код не сработал повторно. */
    void setTotpCounter(UUID uuid, long counter) throws Exception;

    @Override
    void close();
}
