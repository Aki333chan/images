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
import ovh.aurumgg.auth.api.ResetToken;

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

        online.put(uuid, new PlayerState(username, ip, status, premium, false));
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
                setStatus(uuid, AuthStatus.AUTHENTICATED, true);
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

    // -------------------------------------------------------- сброс пароля

    /**
     * Выдать токен сброса по нику.
     *
     * Вызывает администраторский инструмент — панель или команда /auth reset.
     * Игрок при этом может быть не в сети: сброс на то и нужен, что войти он
     * как раз не может.
     *
     * Токен возвращается в открытом виде РОВНО ЗДЕСЬ И ОДИН РАЗ; в базу
     * уходит только его хеш.
     */
    public CompletableFuture<Optional<ResetToken>> issueResetToken(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<AuthAccount> account = repository.findByUsername(username);
                if (account.isEmpty()) return Optional.empty();

                Instant now = clock.get();
                Instant expiresAt = now.plus(config.resetTokenTtl());
                String token = ResetTokens.generate();
                repository.createResetToken(
                        account.get().uuid(), ResetTokens.hash(token), now, expiresAt);
                // Сброс — повод снять блокировку по неудачным попыткам: иначе
                // игрок с токеном упёрся бы в лимит, набранный тем, кто как
                // раз и подбирал его пароль.
                throttle.recordSuccess(account.get().username());
                return Optional.of(new ResetToken(account.get().username(), token, expiresAt));
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Не удалось выдать токен сброса для " + username, e);
                return Optional.empty();
            }
        }, worker);
    }

    /**
     * Первая ступень сброса: игрок вводит токен.
     *
     * Успех НЕ пускает в игру — он лишь переводит игрока в состояние «ждём
     * новый пароль». Пускать здесь значило бы сделать токен полноценным
     * входом, а он одноразовый ключ к смене пароля, и не более.
     */
    public CompletableFuture<AuthOutcome> redeemResetToken(UUID uuid, String token) {
        PlayerState state = online.get(uuid);
        if (state == null) return CompletableFuture.completedFuture(AuthOutcome.error());

        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<UUID> owner =
                        repository.consumeResetToken(ResetTokens.hash(token), clock.get());
                // Токен привязан к аккаунту: выданный для Стива не сработает
                // у того, кто зашёл под другим ником.
                if (owner.isEmpty() || !owner.get().equals(uuid)) {
                    return AuthOutcome.resetTokenInvalid();
                }
                setStatus(uuid, AuthStatus.AWAITING_NEW_PASSWORD);
                return AuthOutcome.resetReady();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Ошибка проверки токена сброса", e);
                return AuthOutcome.error();
            }
        }, worker);
    }

    /**
     * Вторая ступень: новый пароль.
     *
     * Работает только из состояния AWAITING_NEW_PASSWORD — то есть лишь после
     * принятого токена. Проверка обязательна: без неё команда стала бы
     * способом сменить пароль любому, кто просто зашёл под чужим ником.
     */
    public CompletableFuture<AuthOutcome> setNewPassword(
            UUID uuid, char[] password, char[] confirmation, String ip) {
        PlayerState state = online.get(uuid);
        if (state == null || state.status != AuthStatus.AWAITING_NEW_PASSWORD) {
            java.util.Arrays.fill(password, '\0');
            java.util.Arrays.fill(confirmation, '\0');
            return CompletableFuture.completedFuture(AuthOutcome.resetTokenInvalid());
        }
        String username = state.username;

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!java.util.Arrays.equals(password, confirmation)) return AuthOutcome.mismatch();
                String problem = config.validatePassword(new String(password));
                if (problem != null) return AuthOutcome.badPassword(problem);

                Instant now = clock.get();
                repository.updatePasswordHash(uuid, hasher.hash(password.clone()));
                repository.touchLogin(uuid, now, ip);
                sessions.remember(uuid, ip, now);
                throttle.recordSuccess(username);
                setStatus(uuid, AuthStatus.AUTHENTICATED);
                return AuthOutcome.ok("Пароль изменён, вы вошли");
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Ошибка смены пароля игрока " + username, e);
                return AuthOutcome.error();
            } finally {
                java.util.Arrays.fill(password, '\0');
                java.util.Arrays.fill(confirmation, '\0');
            }
        }, worker);
    }

    /**
     * Пропустить вход по праву aurumauth.bypass.
     *
     * Проверить право можно только когда игрок уже в мире: права в Bukkit
     * привязаны к объекту Player, которого на стадии pre-login ещё нет.
     * Поэтому решение принимается здесь, а не в onPreLogin.
     *
     * @return false, если байпас не потребовался (игрок и так вошёл)
     */
    public boolean authenticateByBypass(UUID uuid) {
        PlayerState state = online.get(uuid);
        if (state == null || state.status.isAuthenticated()) return false;
        setStatus(uuid, AuthStatus.AUTHENTICATED_BY_BYPASS);
        // Сессию по байпасу НЕ запоминаем: право может быть снято, и тогда
        // сохранённая сессия ещё пятнадцать минут пускала бы без пароля.
        return true;
    }

    /** Только что зарегистрировался в этой сессии — для приветствия новичка. */
    public boolean isFreshRegistration(UUID uuid) {
        PlayerState state = online.get(uuid);
        return state != null && state.freshRegistration;
    }

    // ------------------------------------------------- администрирование

    /**
     * Снять блокировку по неудачным попыткам.
     *
     * Нужна, когда игрока закрыли чужим перебором: ждать пять минут, объясняя
     * это в чате, — не лучший способ провести вечер.
     */
    public void unlock(String username) {
        throttle.recordSuccess(username);
    }

    /**
     * Разавторизовать игрока в сети и погасить его сессию.
     *
     * Для случая «аккаунт увели»: пароль сменят потом, а перестать пускать
     * нужно прямо сейчас.
     */
    public boolean forceLogout(UUID uuid) {
        sessions.forget(uuid);
        PlayerState state = online.get(uuid);
        if (state == null || !state.status.isAuthenticated()) return false;
        setStatus(uuid, AuthStatus.AWAITING_LOGIN);
        return true;
    }

    /** Сведения об аккаунте для команды /auth info. Блокирующий — из своего пула. */
    public CompletableFuture<Optional<AuthAccount>> lookup(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return repository.findByUsername(username);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Не удалось прочитать аккаунт " + username, e);
                return Optional.<AuthAccount>empty();
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

    /** Периодическая уборка протухших сессий, записей троттлинга и токенов. */
    public void purge() {
        Instant now = clock.get();
        sessions.purgeExpired(now);
        throttle.purgeExpired(now);
        try {
            repository.purgeResetTokens(now);
        } catch (Exception e) {
            // Уборка — дело фоновое: не вышло сейчас, выйдет через пять минут.
            logger.log(Level.WARNING, "Не удалось убрать истёкшие токены сброса", e);
        }
    }

    private void setStatus(UUID uuid, AuthStatus status) {
        setStatus(uuid, status, false);
    }

    private void setStatus(UUID uuid, AuthStatus status, boolean freshRegistration) {
        online.computeIfPresent(uuid, (k, state) -> new PlayerState(
                state.username,
                state.ip,
                status,
                state.premium,
                freshRegistration || state.freshRegistration));
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
    private record PlayerState(
            String username,
            String ip,
            AuthStatus status,
            PremiumVerdict premium,
            boolean freshRegistration) {}
}
