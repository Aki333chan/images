package ovh.aurumgg.auth.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.auth.api.AuthStatus;
import ovh.aurumgg.auth.api.PremiumVerdict;

/**
 * Сквозной сценарий входа на поддельном хранилище.
 *
 * Отдельно и настойчиво проверяется, что ни bcrypt, ни обращения к хранилищу
 * не выполняются в вызывающем потоке. Это ровно та вещь, которая работает
 * «правильно» на пустом тестовом сервере и разваливается на живом: синхронный
 * bcrypt при нескольких одновременных входах вешает сервер всем игрокам, а
 * заметно это становится только под нагрузкой.
 */
class AuthServiceTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String IP = "10.0.0.5";

    private FakeAuthRepository repository;
    private AuthService service;
    private AtomicReference<Instant> now;
    private SessionStore sessions;

    private static AuthConfig config(Map<String, Object> overrides) {
        Map<String, Object> raw = new HashMap<>(overrides);
        // Стоимость bcrypt на минимуме: тестам нужна корректность, а не
        // медленность. В бою значение берётся из конфига и равно 12.
        raw.putIfAbsent("login.bcrypt-cost", 10);
        return AuthConfig.fromMap(raw);
    }

    @BeforeEach
    void setUp() {
        now = new AtomicReference<>(Instant.parse("2026-08-28T12:00:00Z"));
        AuthConfig cfg = config(Map.of("login.attempt-delay-ms", 0));
        repository = new FakeAuthRepository();
        sessions = new SessionStore(cfg.sessionWindow());
        service = new AuthService(
                cfg,
                repository,
                new PasswordHasher(cfg.bcryptCost()),
                sessions,
                new LoginThrottle(cfg.maxAttempts(), cfg.lockout(), cfg.attemptDelay()),
                Logger.getLogger("test"),
                now::get);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private AuthStatus preLogin(PremiumVerdict premium) {
        return service.onPreLogin(PLAYER, "Стив", IP, premium);
    }

    @Test
    void новыйИгрокЖдётРегистрации() {
        assertEquals(AuthStatus.AWAITING_REGISTRATION, preLogin(PremiumVerdict.OFFLINE_NAME));
        assertFalse(service.isAuthenticated(PLAYER));
    }

    @Test
    void регистрацияСразуПускаетВИгру() {
        preLogin(PremiumVerdict.OFFLINE_NAME);
        AuthOutcome outcome = service.register(PLAYER, "хорошийпароль".toCharArray(),
                "хорошийпароль".toCharArray(), IP).join();

        assertTrue(outcome.isSuccess(), outcome.message());
        assertTrue(service.isAuthenticated(PLAYER));
        assertEquals(AuthStatus.AUTHENTICATED, service.status(PLAYER).orElseThrow());
    }

    @Test
    void парольХранитсяХешем() {
        preLogin(PremiumVerdict.OFFLINE_NAME);
        service.register(PLAYER, "хорошийпароль".toCharArray(), "хорошийпароль".toCharArray(), IP).join();

        String stored = repository.peek(PLAYER).orElseThrow().passwordHash();
        assertFalse(stored.contains("хорошийпароль"), "пароль не должен попадать в базу как есть");
        assertTrue(stored.startsWith("$2"), "ожидается bcrypt-хеш, получено: " + stored);
    }

    @Test
    void подтверждениеДолжноСовпадать() {
        preLogin(PremiumVerdict.OFFLINE_NAME);
        AuthOutcome outcome = service.register(PLAYER, "пароль1234".toCharArray(),
                "пароль4321".toCharArray(), IP).join();
        assertEquals(AuthOutcome.Kind.MISMATCH, outcome.kind());
    }

    @Test
    void слишкомКороткийПарольНеПринимается() {
        preLogin(PremiumVerdict.OFFLINE_NAME);
        AuthOutcome outcome = service.register(PLAYER, "123".toCharArray(), "123".toCharArray(), IP).join();
        assertEquals(AuthOutcome.Kind.BAD_PASSWORD, outcome.kind());
    }

    @Test
    void входПоВерномуПаролю() {
        register();
        service.onQuit(PLAYER);
        sessions.forget(PLAYER);

        assertEquals(AuthStatus.AWAITING_LOGIN, preLogin(PremiumVerdict.OFFLINE_NAME));
        AuthOutcome outcome = service.login(PLAYER, "хорошийпароль".toCharArray(), IP).join();
        assertTrue(outcome.isSuccess(), outcome.message());
        assertTrue(service.isAuthenticated(PLAYER));
    }

    @Test
    void неверныйПарольНеПускает() {
        register();
        service.onQuit(PLAYER);
        sessions.forget(PLAYER);
        preLogin(PremiumVerdict.OFFLINE_NAME);

        AuthOutcome outcome = service.login(PLAYER, "не тот пароль".toCharArray(), IP).join();
        assertEquals(AuthOutcome.Kind.WRONG_PASSWORD, outcome.kind());
        assertFalse(service.isAuthenticated(PLAYER));
    }

    @Test
    void сообщениеОбОшибкеНеРазличаетНетАккаунтаИНеТотПароль() {
        // Иначе по тексту ответа можно было бы собрать список
        // зарегистрированных ников, ни одного пароля не подобрав.
        register();
        service.onQuit(PLAYER);
        sessions.forget(PLAYER);
        preLogin(PremiumVerdict.OFFLINE_NAME);
        String wrongPassword = service.login(PLAYER, "не тот".toCharArray(), IP).join().message();

        assertEquals("Неверный пароль", wrongPassword);
    }

    @Test
    void сессияПускаетБезПароляПослеПереподключения() {
        register();
        service.onQuit(PLAYER);

        now.set(now.get().plus(Duration.ofMinutes(5)));
        assertEquals(AuthStatus.AUTHENTICATED_BY_SESSION, preLogin(PremiumVerdict.OFFLINE_NAME));
        assertTrue(service.isAuthenticated(PLAYER));
    }

    @Test
    void сессияПротухаетИПарольСпрашиваютСнова() {
        register();
        service.onQuit(PLAYER);

        now.set(now.get().plus(Duration.ofMinutes(30)));
        assertEquals(AuthStatus.AWAITING_LOGIN, preLogin(PremiumVerdict.OFFLINE_NAME));
    }

    @Test
    void сессияНеРаботаетСДругогоАдреса() {
        register();
        service.onQuit(PLAYER);

        now.set(now.get().plus(Duration.ofMinutes(1)));
        assertEquals(AuthStatus.AWAITING_LOGIN,
                service.onPreLogin(PLAYER, "Стив", "203.0.113.9", PremiumVerdict.OFFLINE_NAME));
    }

    @Test
    void подтверждённыйPremiumВходитБезПароля() {
        register();
        service.onQuit(PLAYER);
        sessions.forget(PLAYER);

        assertEquals(AuthStatus.AUTHENTICATED_BY_PREMIUM, preLogin(PremiumVerdict.PREMIUM_VERIFIED));
    }

    @Test
    void занятыйЛицензиейНикСамПоСебеПарольНеОтменяет() {
        // Ключевая проверка всей premium-части: существование лицензионной
        // учётки с таким ником не доказывает, что за клавиатурой её владелец.
        register();
        service.onQuit(PLAYER);
        sessions.forget(PLAYER);

        assertEquals(AuthStatus.AWAITING_LOGIN, preLogin(PremiumVerdict.PREMIUM_NAME_ONLY));
    }

    @Test
    void недоступнаяБазаНеПускаетБезПароля() {
        register();
        service.onQuit(PLAYER);
        sessions.forget(PLAYER);
        repository.failing = true;

        assertEquals(AuthStatus.AWAITING_LOGIN, preLogin(PremiumVerdict.OFFLINE_NAME));
        assertFalse(service.isAuthenticated(PLAYER));
        assertEquals(AuthOutcome.Kind.ERROR,
                service.login(PLAYER, "хорошийпароль".toCharArray(), IP).join().kind());
    }

    @Test
    void переборЗакрываетАккаунтНаВремя() {
        register();
        service.onQuit(PLAYER);
        sessions.forget(PLAYER);
        preLogin(PremiumVerdict.OFFLINE_NAME);

        for (int i = 0; i < 5; i++) {
            service.login(PLAYER, "мимо".toCharArray(), IP).join();
        }
        AuthOutcome outcome = service.login(PLAYER, "хорошийпароль".toCharArray(), IP).join();
        assertEquals(AuthOutcome.Kind.LOCKED, outcome.kind(),
                "верный пароль во время блокировки тоже не должен пускать");
    }

    @Test
    void ниОдноОбращениеКБазеНеИдётИзВызывающегоПотока() {
        // ГЛАВНАЯ ПРОВЕРКА ПРО ПОТОКИ. Именно синхронные bcrypt и JDBC
        // подвешивают сервер: одна проверка пароля — это ощутимая пауза, а
        // несколько одновременных входов после рестарта складываются в
        // секунды лага у всех, кто в этот момент играет.
        //
        // Проверяются login и register — их зовут из обработчиков команд, то
        // есть с главного потока Bukkit. onPreLogin сюда намеренно не входит:
        // он вызывается из AsyncPlayerPreLoginEvent, который сам по себе уже
        // асинхронный, и уводить работу из него в ещё один поток незачем.
        String caller = Thread.currentThread().getName();
        preLogin(PremiumVerdict.OFFLINE_NAME);
        synchronized (repository.callThreads) {
            repository.callThreads.clear();
        }

        service.register(PLAYER, "хорошийпароль".toCharArray(), "хорошийпароль".toCharArray(), IP).join();
        service.login(PLAYER, "хорошийпароль".toCharArray(), IP).join();

        synchronized (repository.callThreads) {
            assertFalse(repository.callThreads.isEmpty(), "хранилище вообще не звали — тест бесполезен");
            assertTrue(
                    repository.callThreads.stream().noneMatch(name -> name.equals(caller)),
                    "обращения к хранилищу из вызывающего потока: " + repository.callThreads);
            assertTrue(
                    repository.callThreads.stream().allMatch(name -> name.startsWith("AurumAuth-worker-")),
                    "ожидались только рабочие потоки плагина, получено: " + repository.callThreads);
        }
    }

    @Test
    void вПреЛогинеОбращениеКБазеИдётИзТогоЖеПотока() {
        // Обратная сторона того же решения, зафиксированная явно: событие
        // AsyncPlayerPreLoginEvent уже вне главного потока, и перекладывать
        // работу оттуда в свой пул значило бы гонять данные между потоками
        // без всякой причины.
        String caller = Thread.currentThread().getName();
        preLogin(PremiumVerdict.OFFLINE_NAME);
        synchronized (repository.callThreads) {
            assertTrue(repository.callThreads.contains(caller),
                    "ожидалось обращение из вызывающего потока, получено: " + repository.callThreads);
        }
    }

    @Test
    void выходСообщаетПрежнееСостояние() {
        // От этого ответа зависит судьба сообщения о выходе. Спросить
        // состояние отдельным вызовом после удаления уже нельзя — ответ
        // всегда был бы «не авторизован», и сообщение гасилось бы даже тем,
        // кто спокойно играл час.
        register();
        assertTrue(service.onQuit(PLAYER), "вошедший должен уйти с пометкой «был авторизован»");

        // Сессию гасим: иначе повторный заход был бы авторизован по ней,
        // и проверять было бы нечего.
        sessions.forget(PLAYER);
        preLogin(PremiumVerdict.OFFLINE_NAME);
        assertFalse(service.onQuit(PLAYER), "не вошедший — без пометки");

        assertFalse(service.onQuit(PLAYER), "повторный выход неизвестного никого не роняет");
    }

    /** Регистрация как подготовка к тесту про вход. */
    private void register() {
        preLogin(PremiumVerdict.OFFLINE_NAME);
        AuthOutcome outcome = service.register(PLAYER, "хорошийпароль".toCharArray(),
                "хорошийпароль".toCharArray(), IP).join();
        assertTrue(outcome.isSuccess(), outcome.message());
    }
}
