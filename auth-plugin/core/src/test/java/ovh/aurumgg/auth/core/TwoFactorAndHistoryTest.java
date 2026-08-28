package ovh.aurumgg.auth.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.auth.api.AuthStatus;
import ovh.aurumgg.auth.api.PremiumVerdict;
import ovh.aurumgg.auth.core.totp.Base32;
import ovh.aurumgg.auth.core.totp.Totp;

/**
 * Двухфакторка, история входов и удаление регистрации.
 *
 * Проверяется в первую очередь то, что должно НЕ работать: вход по одному
 * паролю при включённой двухфакторке, повторный ввод того же кода, удаление
 * чужой регистрации без пароля. Каждая из этих ошибок выглядит на экране как
 * успешный вход — то есть никак.
 */
class TwoFactorAndHistoryTest {

    private static final UUID STEVE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String IP = "10.0.0.5";
    private static final String PASSWORD = "хорошийпароль";

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
        raw.put("server-id", "выживание");
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

    private void register() {
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);
        assertTrue(service.register(STEVE, PASSWORD.toCharArray(), PASSWORD.toCharArray(), IP)
                .join().isSuccess());
    }

    private void relogin() {
        service.onQuit(STEVE);
        sessions.forget(STEVE);
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);
    }

    /** Код, который приложение показало бы игроку прямо сейчас. */
    private String codeNow() {
        String secret = repository.peek(STEVE).orElseThrow().totpSecret();
        return Totp.code(Base32.decode(secret), Totp.counter(now.get()), Totp.DIGITS);
    }

    /**
     * Включить двухфакторку целиком: секрет плюс подтверждение кодом.
     *
     * Время после подтверждения сдвигается на интервал вперёд намеренно: код,
     * которым подтвердили настройку, засчитан как использованный, и войти им
     * же нельзя. В жизни это ровно то, что происходит — пока игрок дойдёт до
     * входа, приложение покажет уже следующий код.
     */
    private void enableTotp() {
        assertTrue(service.beginTotpSetup(STEVE, "Aurum").join().isPresent());
        assertTrue(service.confirmTotp(STEVE, codeNow()).join().isSuccess());
        now.set(now.get().plusSeconds(60));
    }

    // ------------------------------------------------------- двухфакторка

    @Test
    void доПодтвержденияДвухфакторкаНеДействует() {
        // Между «сгенерировали секрет» и «игрок подтвердил кодом» есть
        // промежуток. Включить её в нём значило бы запереть человека снаружи
        // собственного аккаунта, если он не успел добавить секрет в приложение.
        register();
        service.beginTotpSetup(STEVE, "Aurum").join();
        relogin();

        assertTrue(service.login(STEVE, PASSWORD.toCharArray(), IP).join().isSuccess());
    }

    @Test
    void послеВключенияОдногоПароляМало() {
        register();
        enableTotp();
        relogin();

        AuthOutcome outcome = service.login(STEVE, PASSWORD.toCharArray(), IP).join();
        assertEquals(AuthOutcome.Kind.TOTP_REQUIRED, outcome.kind());
        assertFalse(service.isAuthenticated(STEVE), "пароль принят, но в игру ещё рано");
        assertEquals(AuthStatus.AWAITING_TOTP, service.status(STEVE).orElseThrow());
    }

    @Test
    void сВернымКодомВходПроходит() {
        register();
        enableTotp();
        relogin();
        service.login(STEVE, PASSWORD.toCharArray(), IP).join();

        assertTrue(service.submitTotp(STEVE, codeNow(), IP).join().isSuccess());
        assertTrue(service.isAuthenticated(STEVE));
    }

    @Test
    void чужойКодНеПускает() {
        register();
        enableTotp();
        relogin();
        service.login(STEVE, PASSWORD.toCharArray(), IP).join();

        assertEquals(AuthOutcome.Kind.TOTP_INVALID, service.submitTotp(STEVE, "000000", IP).join().kind());
        assertFalse(service.isAuthenticated(STEVE));
    }

    @Test
    void тотЖеКодВторойРазНеСрабатывает() {
        // САМАЯ ВАЖНАЯ ПРОВЕРКА ЗДЕСЬ. Код живёт полминуты, и подсмотренный
        // через плечо (или продиктованный «сотруднику поддержки») он всё это
        // время годен снова.
        register();
        enableTotp();
        relogin();
        service.login(STEVE, PASSWORD.toCharArray(), IP).join();
        String code = codeNow();
        assertTrue(service.submitTotp(STEVE, code, IP).join().isSuccess());

        relogin();
        service.login(STEVE, PASSWORD.toCharArray(), IP).join();
        assertEquals(AuthOutcome.Kind.TOTP_INVALID, service.submitTotp(STEVE, code, IP).join().kind());
    }

    @Test
    void кодомПодтвержденияВойтиНельзя() {
        // Подтверждение настройки расходует свой интервал наравне со входом:
        // иначе код, который игрок только что ввёл при включении, оставался бы
        // рабочим ключом ещё полминуты.
        register();
        assertTrue(service.beginTotpSetup(STEVE, "Aurum").join().isPresent());
        String setupCode = codeNow();
        assertTrue(service.confirmTotp(STEVE, setupCode).join().isSuccess());

        relogin();
        service.login(STEVE, PASSWORD.toCharArray(), IP).join();
        assertEquals(AuthOutcome.Kind.TOTP_INVALID,
                service.submitTotp(STEVE, setupCode, IP).join().kind());
    }

    @Test
    void следующийКодПослеПовтораРаботает() {
        // Обратная сторона защиты от повтора: она не должна запирать игрока
        // навсегда — следующий интервал обязан приниматься.
        register();
        enableTotp();
        relogin();
        service.login(STEVE, PASSWORD.toCharArray(), IP).join();
        service.submitTotp(STEVE, codeNow(), IP).join();

        now.set(now.get().plusSeconds(60));
        relogin();
        service.login(STEVE, PASSWORD.toCharArray(), IP).join();
        assertTrue(service.submitTotp(STEVE, codeNow(), IP).join().isSuccess());
    }

    @Test
    void кодБезВведённогоПароляНеПринимается() {
        // Иначе второй фактор стал бы первым и единственным.
        register();
        enableTotp();
        relogin();

        assertEquals(AuthOutcome.Kind.ERROR, service.submitTotp(STEVE, codeNow(), IP).join().kind());
        assertFalse(service.isAuthenticated(STEVE));
    }

    @Test
    void выключитьМожноТолькоДействующимКодом() {
        // Иначе двухфакторку снял бы любой, кто на минуту сел за компьютер с
        // уже вошедшим игроком.
        register();
        enableTotp();

        assertEquals(AuthOutcome.Kind.TOTP_INVALID, service.disableTotp(STEVE, "000000").join().kind());
        assertTrue(repository.peek(STEVE).orElseThrow().hasTotp());

        assertTrue(service.disableTotp(STEVE, codeNow()).join().isSuccess());
        assertFalse(repository.peek(STEVE).orElseThrow().hasTotp());
    }

    @Test
    void администраторСнимаетДвухфакторкуПотерявшемуТелефон() {
        // Без этого потерянный телефон означает потерянный навсегда аккаунт.
        register();
        enableTotp();

        assertTrue(service.disableTotpByAdmin("Стив").join());
        assertFalse(repository.peek(STEVE).orElseThrow().hasTotp());
        assertFalse(service.disableTotpByAdmin("Стив").join(), "второй раз снимать уже нечего");
    }

    @Test
    void секретИСсылкаВыдаютсяДляПриложения() {
        register();
        AuthService.TotpSetup setup = service.beginTotpSetup(STEVE, "Aurum").join().orElseThrow();
        assertEquals(32, setup.secret().length());
        assertTrue(setup.otpauthUri().startsWith("otpauth://totp/Aurum:"), setup.otpauthUri());
        assertTrue(setup.otpauthUri().contains(setup.secret()));
    }

    // ---------------------------------------------------- история входов

    @Test
    void успешныйВходПопадаетВИсторию() {
        register();
        relogin();
        service.login(STEVE, PASSWORD.toCharArray(), IP).join();

        List<LoginRecord> history = service.loginHistory("Стив", Duration.ofDays(1), 50).join();
        assertTrue(history.stream().anyMatch(r -> r.result() == LoginRecord.Result.SUCCESS));
        assertTrue(history.stream().allMatch(r -> IP.equals(r.ip())));
        assertTrue(history.stream().allMatch(r -> "выживание".equals(r.serverId())),
                "база одна на сеть, и без имени сервера непонятно, где заходили");
    }

    @Test
    void неудачныеПопыткиТожеЗаписываются() {
        // Ради них история и нужна: серия неудач перед успешным входом — это
        // и есть картина «пароль подбирали, и подобрали».
        register();
        relogin();
        service.login(STEVE, "мимо".toCharArray(), IP).join();
        service.login(STEVE, "мимо".toCharArray(), IP).join();

        List<LoginRecord> history = service.loginHistory("Стив", Duration.ofDays(1), 50).join();
        assertEquals(2, history.stream()
                .filter(r -> r.result() == LoginRecord.Result.WRONG_PASSWORD).count());
    }

    @Test
    void входБезПароляРазличимВИстории() {
        // «Пустили по сессии» и «ввёл пароль» — разные события, и при разборе
        // инцидента разница принципиальна.
        register();
        service.onQuit(STEVE);
        now.set(now.get().plus(Duration.ofMinutes(1)));
        service.onPreLogin(STEVE, "Стив", IP, PremiumVerdict.OFFLINE_NAME);
        // Запись идёт в фоне — дожидаемся её через синхронный вызов того же пула.
        service.loginHistory("Стив", Duration.ofDays(1), 50).join();

        List<LoginRecord> history = service.loginHistory("Стив", Duration.ofDays(1), 50).join();
        assertTrue(history.stream().anyMatch(r -> r.result() == LoginRecord.Result.SESSION));
    }

    @Test
    void периодОграничиваетВыборку() {
        register();
        relogin();
        service.login(STEVE, PASSWORD.toCharArray(), IP).join();

        now.set(now.get().plus(Duration.ofDays(5)));
        assertTrue(service.loginHistory("Стив", Duration.ofDays(1), 50).join().isEmpty());
        assertFalse(service.loginHistory("Стив", Duration.ofDays(7), 50).join().isEmpty());
    }

    @Test
    void неудачныйКодДвухфакторкиВиденОтдельно() {
        register();
        enableTotp();
        relogin();
        service.login(STEVE, PASSWORD.toCharArray(), IP).join();
        service.submitTotp(STEVE, "000000", IP).join();

        List<LoginRecord> history = service.loginHistory("Стив", Duration.ofDays(1), 50).join();
        assertTrue(history.stream().anyMatch(r -> r.result() == LoginRecord.Result.WRONG_CODE));
    }

    // ------------------------------------------------ удаление регистрации

    @Test
    void игрокУдаляетСвоюРегистрациюПоПаролю() {
        register();
        assertTrue(service.unregisterSelf(STEVE, PASSWORD.toCharArray()).join().isSuccess());
        assertTrue(repository.peek(STEVE).isEmpty());
        assertFalse(service.isAuthenticated(STEVE));
        assertEquals(AuthStatus.AWAITING_REGISTRATION, service.status(STEVE).orElseThrow());
    }

    @Test
    void безПароляСвояРегистрацияНеУдаляется() {
        // Команда стирает аккаунт, и подтверждение здесь единственное, что
        // отличает решение владельца от шутки того, кто сел за его компьютер.
        register();
        assertEquals(AuthOutcome.Kind.WRONG_PASSWORD,
                service.unregisterSelf(STEVE, "мимо".toCharArray()).join().kind());
        assertTrue(repository.peek(STEVE).isPresent());
    }

    @Test
    void послеУдаленияНикСвободенИРегистрируетсяЗаново() {
        register();
        service.unregisterSelf(STEVE, PASSWORD.toCharArray()).join();

        assertTrue(service.register(STEVE, "другойпароль".toCharArray(),
                "другойпароль".toCharArray(), IP).join().isSuccess());
    }

    @Test
    void администраторСнимаетРегистрацию() {
        register();
        assertTrue(service.unregisterByAdmin("Стив").join());
        assertTrue(repository.peek(STEVE).isEmpty());
        assertFalse(service.isAuthenticated(STEVE), "игрок в сети сразу перестаёт быть вошедшим");
        assertFalse(service.unregisterByAdmin("Стив").join(), "второй раз снимать уже нечего");
    }

    @Test
    void удалениеУноситТокеныСбросаНоНеИсторию() {
        // Оставленный токен от удалённого аккаунта — мусор, который однажды
        // сработает не на том, кого ждали. История, наоборот, про то, что
        // происходило, и удаление аккаунта этого не отменяет.
        register();
        relogin();
        service.login(STEVE, PASSWORD.toCharArray(), IP).join();
        String token = service.issueResetToken("Стив").join().orElseThrow().token();

        service.unregisterByAdmin("Стив").join();

        assertEquals(AuthOutcome.Kind.RESET_TOKEN_INVALID,
                service.redeemResetToken(STEVE, token).join().kind());
        assertFalse(service.loginHistory("Стив", Duration.ofDays(1), 50).join().isEmpty());
    }

    @Test
    void удалениеГаситСессию() {
        // Иначе переподключение вернуло бы доступ по несуществующему аккаунту.
        register();
        service.unregisterByAdmin("Стив").join();
        assertFalse(sessions.isValid(STEVE, IP, now.get()));
    }
}
