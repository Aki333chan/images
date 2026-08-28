package ovh.aurumgg.auth.core;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import ovh.aurumgg.auth.api.AuthStatus;
import ovh.aurumgg.auth.api.PremiumVerdict;

/**
 * Вся работа с аккаунтами: регистрация, вход, состояние игроков в сети.
 *
 * ЕДИНСТВЕННОЕ МЕСТО, ГДЕ ВООБЩЕ ЕСТЬ ПОХОДЫ В БД И BCRYPT — и ровно поэтому
 * оно же единственное, где нужно следить за потоками. Все такие операции здесь
 * уходят в собственный пул и возвращают CompletableFuture; вызывающая сторона
 * (слой Bukkit) обязана вернуться на главный поток сама, прежде чем что-то
 * делать с игроком.
 *
 * ПОЧЕМУ ЭТО НЕ ФОРМАЛЬНОСТЬ. bcrypt медленный намеренно: при стоимости 12
 * одна проверка — около четверти секунды. Синхронный вызов означает четверть
 * секунды полностью остановленного сервера, а несколько одновременных заходов
 * (обычное дело после рестарта) — секунды заметного всем лага. Причём страдают
 * не логинящиеся, а вообще все, кто в этот момент в игре.
 *
 * Состояние игроков в сети, наоборот, лежит в памяти и читается мгновенно —
 * именно его отдаёт публичный API, и поэтому isAuthenticated можно спокойно
 * звать с главного потока хоть каждый тик.
 */
public final class AuthService implements AutoCloseable {

    private final AuthConfig config;
    private final AuthRepository repository;
    private final PasswordHasher hasher;
    private final SessionStore sessions;
    private final LoginThrottle throttle;
    private final Logger logger;
    private final ExecutorService worker;
    private final Supplier<Instant> clock;

    /** Игроки в сети: UUID → что мы о них знаем прямо сейчас. */
    private final Map<UUID, PlayerState> online = new ConcurrentHashMap<>();

    public AuthService(
            AuthConfig config,
            AuthRepository repository,
            PasswordHasher hasher,
            SessionStore sessions,
            LoginThrottle throttle,
            Logger logger,
            Supplier<Instant> clock) {
        this.config = config;
        this.repository = repository;
        this.hasher = hasher;
        this.sessions = sessions;
        this.throttle = throttle;
        this.logger = logger;
        this.clock = clock;
        // Пул небольшой и с понятным именем: в дампе потоков сразу видно, чем
        // занят сервер. Размер по пулу соединений — упираться будем в БД, а
        // держать больше потоков, чем есть соединений, бессмысленно.
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "AurumAuth-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.worker = Executors.newFixedThreadPool(Math.max(2, config.poolSize()), factory);
    }

    // ------------------------------------------------------------- состояние

    /**
     * Игрок дошёл до стадии подключения (AsyncPlayerPreLoginEvent).
     *
     * Вызывается уже вне главного потока, поэтому обращение к БД здесь
     * прямое — и это единственное место, где так можно.
     */
    public AuthStatus onPreLogin(UUID uuid, String username, String ip, PremiumVerdict premium) {
        AuthStatus status;
        try {
            Optional<AuthAccount> account = repository.findByUuid(uuid);
            if (account.isEmpty()) {
                status = AuthStatus.AWAITING_REGISTRATION;
            } else if (config.premiumSkipPassword() && premium.allowsPasswordBypass()) {
                // Вход уже подтверждён вышестоящим звеном в online-mode —
                // см. PremiumVerdict.PREMIUM_VERIFIED.
                status = AuthStatus.AUTHENTICATED_BY_PREMIUM;
            } else if (sessions.isValid(uuid, ip, clock.get())) {
                status = AuthStatus.AUTHENTICATED_BY_SESSION;
            } else {
                status = AuthStatus.AWAITING_LOGIN;
            }
        } catch (Exception e) {
            // БД недоступна. Пускать без пароля нельзя ни в коем случае, но и
            // молча ронять вход тоже: пусть игрок увидит осмысленный отказ.
            logger.log(Level.SEVERE, "Не удалось прочитать аккаунт " + username, e);
            status = AuthStatus.AWAITING_LOGIN;
        }

        online.put(uuid, new PlayerState(username, ip, status, premium));
        if (status.isAuthenticated()) {
            markLoggedIn(uuid, username, ip);
        }
        return status;
    }

    /**
     * Игрок вышел; в ответе — был ли он авторизован на этот момент.
     *
     * Состояние возвращается намеренно. Оно нужно тому, кто решает судьбу
     * сообщения о выходе, а спросить его отдельным вызовом после удаления уже
     * нельзя — ответ всегда будет «не авторизован». Возврат делает уборку и
     * чтение одной неделимой операцией, и порядок слушателей перестаёт
     * что-либо решать.
     */
    public boolean onQuit(UUID uuid) {
        PlayerState removed = online.remove(uuid);
        return removed != null && removed.status.isAuthenticated();
    }

    public boolean isAuthenticated(UUID uuid) {
        PlayerState state = online.get(uuid);
        return state != null && state.status.isAuthenticated();
    }

    public Optional<AuthStatus> status(UUID uuid) {
        return Optional.ofNullable(online.get(uuid)).map(state -> state.status);
    }

    public Optional<PremiumVerdict> premiumVerdict(UUID uuid) {
        return Optional.ofNullable(online.get(uuid)).map(state -> state.premium);
    }

    /** Ники всех, кто сейчас в сети и ещё не вошёл. */
    public int awaitingCount() {
        return (int) online.values().stream().filter(state -> !state.status.isAuthenticated()).count();
    }

    // ---------------------------------------------------------------- вход

    /**
     * Проверка пароля.
     *
     * Возвращает future намеренно: и чтение из БД, и bcrypt внутри, и звать
     * это с главного потока нельзя. Результат применять тоже надо на главном —
     * вернуться туда обязан вызывающий.
     */
    public CompletableFuture<AuthOutcome> login(UUID uuid, char[] password, String ip) {
        PlayerState state = online.get(uuid);
        if (state == null) {
            // Игрок отключился, пока команда шла до обработчика.
            return CompletableFuture.completedFuture(AuthOutcome.error());
        }
        String username = state.username;

        return CompletableFuture.supplyAsync(() -> {
            Instant now = clock.get();
            LoginThrottle.Decision decision = throttle.check(username, now);
            if (!decision.allowed()) {
                // Пароль при отказе даже не проверяем — иначе лимит попыток не
                // ограничивал бы нагрузку на bcrypt, а только текст ответа.
                java.util.Arrays.fill(password, '\0');
                return decision.lockedOut()
                        ? AuthOutcome.locked(decision.retryAfter())
                        : AuthOutcome.throttled(decision.retryAfter());
            }

            try {
                Optional<AuthAccount> found = repository.findByUuid(uuid);
                if (found.isEmpty()) {
                    java.util.Arrays.fill(password, '\0');
                    return AuthOutcome.notRegistered();
                }
                if (!hasher.verify(password, found.get().passwordHash())) {
                    throttle.recordFailure(username, now);
                    return AuthOutcome.wrongPassword();
                }
                throttle.recordSuccess(username);
                repository.touchLogin(uuid, now, ip);
                sessions.remember(uuid, ip, now);
                setStatus(uuid, AuthStatus.AUTHENTICATED);
                return AuthOutcome.ok("Вы вошли");
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Ошибка входа игрока " + username, e);
                return AuthOutcome.error();
            }
        }, worker);
    }

    /** Регистрация нового аккаунта. Так же асинхронна и по той же причине. */
    public CompletableFuture<AuthOutcome> register(
            UUID uuid, char[] password, char[] confirmation, String ip) {
        PlayerState state = online.get(uuid);
        if (state == null) return CompletableFuture.completedFuture(AuthOutcome.error());
        String username = state.username;

        return CompletableFuture.supplyAsync(() -> {
            String asString = new String(password);
            try {
                if (!java.util.Arrays.equals(password, confirmation)) {
                    return AuthOutcome.mismatch();
                }
                String problem = config.validatePassword(asString);
                if (problem != null) return AuthOutcome.badPassword(problem);

                // Ник проверяется отдельно от UUID: на смешанном сервере один
                // ник даёт разные UUID у лицензионного и пиратского клиента, и
                // без этой проверки на один ник завелись бы два аккаунта.
                if (repository.findByUsername(username).isPresent()) {
                    return AuthOutcome.alreadyRegistered();
                }

                Instant now = clock.get();
                String hash = hasher.hash(password.clone());
                repository.create(new AuthAccount(uuid, username, hash, null, now, null, ip));
                repository.touchLogin(uuid, now, ip);
                sessions.remember(uuid, ip, now);
                throttle.recordSuccess(username);
                setStatus(uuid, AuthStatus.AUTHENTICATED);
                return AuthOutcome.ok("Регистрация завершена, вы вошли");
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Ошибка регистрации игрока " + username, e);
                return AuthOutcome.error();
            } finally {
                java.util.Arrays.fill(password, '\0');
                java.util.Arrays.fill(confirmation, '\0');
            }
        }, worker);
    }

    /** Зарегистрирован ли ник. Блокирующий вызов — только из своего пула. */
    public CompletableFuture<Boolean> isRegistered(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return repository.findByUsername(username).isPresent();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Не удалось проверить ник " + username, e);
                return false;
            }
        }, worker);
    }

    /** Периодическая уборка протухших сессий и записей троттлинга. */
    public void purge() {
        Instant now = clock.get();
        sessions.purgeExpired(now);
        throttle.purgeExpired(now);
    }

    private void setStatus(UUID uuid, AuthStatus status) {
        online.computeIfPresent(uuid, (k, state) ->
                new PlayerState(state.username, state.ip, status, state.premium));
    }

    private void markLoggedIn(UUID uuid, String username, String ip) {
        // Вход по сессии или по premium тоже продлевает сессию: иначе окно
        // отсчитывалось бы от последнего ввода пароля и истекало посреди
        // нормальной игры.
        sessions.remember(uuid, ip, clock.get());
        worker.execute(() -> {
            try {
                repository.touchLogin(uuid, clock.get(), ip);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Не удалось отметить вход игрока " + username, e);
            }
        });
    }

    @Override
    public void close() {
        worker.shutdown();
        try {
            // Даём доработать тому, что уже начато: оборванная на середине
            // регистрация оставила бы игрока без аккаунта, но с уверенностью,
            // что он зарегистрировался.
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
        repository.close();
    }

    /** Неизменяемый снимок состояния игрока в сети. */
    private record PlayerState(String username, String ip, AuthStatus status, PremiumVerdict premium) {}
}
