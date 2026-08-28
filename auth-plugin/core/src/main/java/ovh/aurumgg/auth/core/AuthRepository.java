package ovh.aurumgg.auth.core;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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

    /** Сменить пароль (пока используется только для будущего сброса). */
    void updatePasswordHash(UUID uuid, String passwordHash) throws Exception;

    @Override
    void close();
}
