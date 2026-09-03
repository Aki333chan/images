package ovh.aurumgg.auth.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.auth.api.IpRecord;
import ovh.aurumgg.auth.api.PremiumVerdict;

/**
 * История адресов: что записывается при входе и что видит панель.
 *
 * Сам плагин по этим данным ничего не решает — ни блокировок, ни ограничений.
 * Поэтому проверяется ровно то, ради чего они собираются: список адресов с
 * датами первого и последнего появления.
 */
class IpHistoryTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String HOME = "10.0.0.5";
    private static final String PHONE = "203.0.113.77";

    private AtomicReference<Instant> now;
    private FakeAuthRepository repository;
    private AuthService service;

    @BeforeEach
    void setUp() {
        now = new AtomicReference<>(Instant.parse("2026-08-28T12:00:00Z"));
        // Стоимость bcrypt на минимуме: тесту нужна корректность, а не
        // медленность.
        AuthConfig cfg = AuthConfig.fromMap(
                Map.of("login.attempt-delay-ms", 0, "login.bcrypt-cost", 10));
        repository = new FakeAuthRepository();

        Logger logger = Logger.getLogger("auth-ip-test");
        logger.setLevel(Level.OFF);

        service = new AuthService(
                cfg,
                repository,
                new PasswordHasher(cfg.bcryptCost()),
                new SessionStore(cfg.sessionWindow()),
                new LoginThrottle(cfg.maxAttempts(), cfg.lockout(), cfg.attemptDelay()),
                logger,
                now::get);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private void register(String ip) {
        service.onPreLogin(PLAYER, "Стив", ip, PremiumVerdict.OFFLINE_NAME);
        service.register(PLAYER, "хорошийпароль".toCharArray(), "хорошийпароль".toCharArray(), ip)
                .join();
    }

    private void loginFrom(String ip) {
        service.onPreLogin(PLAYER, "Стив", ip, PremiumVerdict.OFFLINE_NAME);
        service.login(PLAYER, "хорошийпароль".toCharArray(), ip).join();
    }

    @Test
    @DisplayName("Первый вход заводит запись с одинаковыми датами")
    void первыйАдрес() {
        register(HOME);

        List<IpRecord> history = service.ipHistory(PLAYER).join();
        assertEquals(1, history.size());
        assertEquals(HOME, history.get(0).ip());
        assertEquals(history.get(0).firstSeen(), history.get(0).lastSeen(),
                "адрес увидели один раз — обе даты совпадают");
    }

    @Test
    @DisplayName("Повторный вход с того же адреса двигает только last_seen")
    void повторныйВходСТогоЖеАдреса() {
        register(HOME);
        Instant first = service.ipHistory(PLAYER).join().get(0).firstSeen();

        now.set(now.get().plus(Duration.ofDays(3)));
        loginFrom(HOME);

        List<IpRecord> history = service.ipHistory(PLAYER).join();
        assertEquals(1, history.size(), "второй записи о том же адресе быть не должно");
        // В этом весь смысл поля: оно отвечает, когда адрес увидели ВПЕРВЫЕ.
        assertEquals(first, history.get(0).firstSeen());
        assertEquals(now.get(), history.get(0).lastSeen());
    }

    @Test
    @DisplayName("Новый адрес добавляется отдельной записью, старая остаётся")
    void второйАдрес() {
        register(HOME);
        now.set(now.get().plus(Duration.ofDays(1)));
        loginFrom(PHONE);

        List<IpRecord> history = service.ipHistory(PLAYER).join();
        assertEquals(2, history.size());
        // Новые сверху: на вопрос «откуда он заходит сейчас» отвечает первая
        // строка, и листать за этим не надо.
        assertEquals(PHONE, history.get(0).ip());
        assertEquals(HOME, history.get(1).ip());
    }

    @Test
    @DisplayName("Порядок — по последнему появлению, а не по первому")
    void порядокПоПоследнемуПоявлению() {
        register(HOME);
        now.set(now.get().plus(Duration.ofDays(1)));
        loginFrom(PHONE);
        // Вернулся домой — домашний адрес снова самый свежий.
        now.set(now.get().plus(Duration.ofDays(1)));
        loginFrom(HOME);

        List<IpRecord> history = service.ipHistory(PLAYER).join();
        assertEquals(HOME, history.get(0).ip());
        assertEquals(PHONE, history.get(1).ip());
    }

    @Test
    @DisplayName("У незнакомого игрока история пуста, а не падает")
    void незнакомыйИгрок() {
        assertTrue(service.ipHistory(UUID.randomUUID()).join().isEmpty());
    }

    @Test
    @DisplayName("Ники зарегистрированных отдаются в нижнем регистре")
    void никиВНижнемРегистре() {
        register(HOME);
        // Панель сверяет с ними исторический список офлайн-игроков, а тот
        // приходит от Bukkit как есть — сравнивать придётся без учёта
        // регистра, и приводить к нему лучше здесь, чем в каждом вызывающем.
        assertEquals(java.util.Set.of("стив"), service.registeredUsernames().join());
    }
}
