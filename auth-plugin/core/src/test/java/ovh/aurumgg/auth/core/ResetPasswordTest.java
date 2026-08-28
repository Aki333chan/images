package ovh.aurumgg.auth.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import ovh.aurumgg.auth.api.ResetToken;

/**
 * Сброс пароля по токену из панели.
 *
 * Проверяется в первую очередь то, что должно НЕ работать: чужой токен,
 * повторный ввод, попытка задать пароль без токена. Ошибка в любую из этих
 * сторон означает смену чужого пароля, и на экране она никак не проявляется.
 */
class ResetPasswordTest {

    private static final UUID STEVE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ALEX = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final String IP = "10.0.0.5";

    private FakeAuthRepository repository;
    private AuthService service;
    private AtomicReference<Instant> now;
    private SessionStore sessions;

    @BeforeEach
    void setUp() {
        now = new AtomicReference<>(Instant.parse("2026-08-28T12:00:00Z"));
        Map<String, Object> raw = new HashMap<>();
        raw.put("login.bcrypt-cost", 10);
        raw.put("login.attempt-delay-ms", 0);
        AuthConfig cfg = AuthConfig.fromMap(raw);
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

    /** Зарегистрировать Стива и выйти, забыв сессию. */
    private void registerSteve() {
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);
        assertTrue(service.register(STEVE, "старыйпароль".toCharArray(),
                "старыйпароль".toCharArray(), IP).join().isSuccess());
        service.onQuit(STEVE);
        sessions.forget(STEVE);
    }

    private String issue() {
        ResetToken token = service.issueResetToken("Стив").join().orElseThrow();
        assertEquals("Стив", token.username());
        return token.token();
    }

    @Test
    void токенВыдаётсяТолькоСуществующемуАккаунту() {
        registerSteve();
        assertTrue(service.issueResetToken("Стив").join().isPresent());
        assertTrue(service.issueResetToken("НетТакого").join().isEmpty());
    }

    @Test
    void токенЖивётДвадцатьМинутПоУмолчанию() {
        registerSteve();
        ResetToken token = service.issueResetToken("Стив").join().orElseThrow();
        assertEquals(Duration.ofMinutes(20), Duration.between(now.get(), token.expiresAt()));
    }

    @Test
    void токенВБазеЛежитТолькоХешем() {
        // Дамп базы не должен давать возможность войти под теми, кому за
        // последние двадцать минут выдали сброс.
        registerSteve();
        String token = issue();
        assertNotEquals(token, ResetTokens.hash(token));
        assertEquals(64, ResetTokens.hash(token).length());
    }

    @Test
    void полныйСценарийСброса() {
        registerSteve();
        String token = issue();

        // Игрок заходит: пароля он не знает, его ждут на /login.
        assertEquals(AuthStatus.AWAITING_LOGIN,
                service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME));

        // Первая ступень: токен. В игру он ещё НЕ пускает.
        AuthOutcome accepted = service.redeemResetToken(STEVE, token).join();
        assertEquals(AuthOutcome.Kind.RESET_READY, accepted.kind());
        assertFalse(service.isAuthenticated(STEVE), "токен — это не вход");
        assertEquals(AuthStatus.AWAITING_NEW_PASSWORD, service.status(STEVE).orElseThrow());

        // Вторая ступень: новый пароль.
        AuthOutcome done = service.setNewPassword(
                STEVE, "новыйпароль".toCharArray(), "новыйпароль".toCharArray(), IP).join();
        assertTrue(done.isSuccess(), done.message());
        assertTrue(service.isAuthenticated(STEVE));

        // И он действительно работает при следующем входе.
        service.onQuit(STEVE);
        sessions.forget(STEVE);
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);
        assertTrue(service.login(STEVE, "новыйпароль".toCharArray(), IP).join().isSuccess());
    }

    @Test
    void старыйПарольПослеСбросаНеРаботает() {
        registerSteve();
        String token = issue();
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);
        service.redeemResetToken(STEVE, token).join();
        service.setNewPassword(STEVE, "новыйпароль".toCharArray(), "новыйпароль".toCharArray(), IP).join();

        service.onQuit(STEVE);
        sessions.forget(STEVE);
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);
        assertEquals(AuthOutcome.Kind.WRONG_PASSWORD,
                service.login(STEVE, "старыйпароль".toCharArray(), IP).join().kind());
    }

    @Test
    void токенСрабатываетРовноОдинРаз() {
        registerSteve();
        String token = issue();
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);

        assertEquals(AuthOutcome.Kind.RESET_READY, service.redeemResetToken(STEVE, token).join().kind());
        assertEquals(AuthOutcome.Kind.RESET_TOKEN_INVALID,
                service.redeemResetToken(STEVE, token).join().kind());
    }

    @Test
    void чужимТокеномНеВоспользоваться() {
        // Токен привязан к аккаунту. Иначе достаточно было бы подсмотреть
        // чужой токен и зайти под своим ником.
        registerSteve();
        String steveToken = issue();

        service.onPreLogin(ALEX, "Алекс", IP, PremiumVerdict.OFFLINE_NAME);
        assertEquals(AuthOutcome.Kind.RESET_TOKEN_INVALID,
                service.redeemResetToken(ALEX, steveToken).join().kind());
    }

    @Test
    void истёкшийТокенНеПринимается() {
        registerSteve();
        String token = issue();
        now.set(now.get().plus(Duration.ofMinutes(21)));

        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);
        assertEquals(AuthOutcome.Kind.RESET_TOKEN_INVALID,
                service.redeemResetToken(STEVE, token).join().kind());
    }

    @Test
    void новыйТокенГаситПредыдущий() {
        registerSteve();
        String first = issue();
        String second = issue();
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);

        assertEquals(AuthOutcome.Kind.RESET_TOKEN_INVALID,
                service.redeemResetToken(STEVE, first).join().kind());
        assertEquals(AuthOutcome.Kind.RESET_READY,
                service.redeemResetToken(STEVE, second).join().kind());
    }

    @Test
    void парольБезТокенаНеМеняется() {
        // САМАЯ ВАЖНАЯ ПРОВЕРКА ЗДЕСЬ. Без неё вторая ступень стала бы
        // способом сменить пароль любому, кто просто зашёл под чужим ником.
        registerSteve();
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);

        AuthOutcome outcome = service.setNewPassword(
                STEVE, "чужойпароль".toCharArray(), "чужойпароль".toCharArray(), IP).join();
        assertEquals(AuthOutcome.Kind.RESET_TOKEN_INVALID, outcome.kind());
        assertFalse(service.isAuthenticated(STEVE));

        // Старый пароль по-прежнему рабочий.
        assertTrue(service.login(STEVE, "старыйпароль".toCharArray(), IP).join().isSuccess());
    }

    @Test
    void короткийНовыйПарольНеПринимается() {
        registerSteve();
        String token = issue();
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);
        service.redeemResetToken(STEVE, token).join();

        assertEquals(AuthOutcome.Kind.BAD_PASSWORD,
                service.setNewPassword(STEVE, "123".toCharArray(), "123".toCharArray(), IP).join().kind());
        // Состояние сохраняется: человек ошибся, а не потерял право сброса.
        assertEquals(AuthStatus.AWAITING_NEW_PASSWORD, service.status(STEVE).orElseThrow());
    }

    @Test
    void сбросСнимаетБлокировкуПоПопыткам() {
        // Иначе игрок с токеном упирался бы в лимит, набранный тем, кто как
        // раз и подбирал его пароль.
        registerSteve();
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);
        for (int i = 0; i < 5; i++) service.login(STEVE, "мимо".toCharArray(), IP).join();
        assertEquals(AuthOutcome.Kind.LOCKED, service.login(STEVE, "мимо".toCharArray(), IP).join().kind());

        String token = issue();
        assertEquals(AuthOutcome.Kind.RESET_READY, service.redeemResetToken(STEVE, token).join().kind());
        assertTrue(service.setNewPassword(
                STEVE, "новыйпароль".toCharArray(), "новыйпароль".toCharArray(), IP).join().isSuccess());
    }

    @Test
    void регистрТокенаНеВажен() {
        // Игрок наберёт его как получится, а буквы в токене заглавные только
        // ради читаемости.
        registerSteve();
        String token = issue();
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);
        assertEquals(AuthOutcome.Kind.RESET_READY,
                service.redeemResetToken(STEVE, " " + token.toLowerCase(java.util.Locale.ROOT) + " ")
                        .join().kind());
    }

    @Test
    void токенОтличимОтПароляПоВиду() {
        // По этому различию команда /reset понимает, что ей передали:
        // «/reset ABCD2345» или «/reset новыйпароль подтверждение».
        assertTrue(ResetTokens.looksLikeToken("ABCD2345"));
        assertTrue(ResetTokens.looksLikeToken("abcd2345"));
        assertFalse(ResetTokens.looksLikeToken("короткий"));
        assertFalse(ResetTokens.looksLikeToken("ABCD234"));
        // Ноль, единица и буква O в алфавит не входят — их легко перепутать.
        assertFalse(ResetTokens.looksLikeToken("ABCD2340"));
        assertFalse(ResetTokens.looksLikeToken(null));
    }

    @Test
    void разавторизацияГаситСессию() {
        registerSteve();
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);
        service.login(STEVE, "старыйпароль".toCharArray(), IP).join();
        assertTrue(service.isAuthenticated(STEVE));

        assertTrue(service.forceLogout(STEVE));
        assertFalse(service.isAuthenticated(STEVE));
        // И сессия тоже: иначе переподключение вернуло бы доступ без пароля.
        assertFalse(sessions.isValid(STEVE, IP, now.get()));
    }
}
