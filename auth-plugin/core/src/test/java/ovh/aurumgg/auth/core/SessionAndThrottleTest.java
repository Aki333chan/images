package ovh.aurumgg.auth.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Сессии, лимит попыток, отложенные сообщения и разбор конфига.
 *
 * Всё это — вещи, которые ломаются молча: сессия, которая не протухает,
 * блокировка, которая не снимается, и настройка «таймаут 0 секунд», принятая
 * без возражений. На работающем сервере ни одно из этого не выглядит как
 * ошибка, пока не станет поздно.
 */
class SessionAndThrottleTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-08-28T12:00:00Z");
    private static final String IP = "10.0.0.5";

    // ------------------------------------------------------------- сессии

    @Test
    void свежаяСессияПускаетБезПароля() {
        SessionStore store = new SessionStore(Duration.ofMinutes(15));
        store.remember(PLAYER, IP, T0);
        assertTrue(store.isValid(PLAYER, IP, T0.plus(Duration.ofMinutes(14))));
    }

    @Test
    void протухшаяСессияНеПускает() {
        SessionStore store = new SessionStore(Duration.ofMinutes(15));
        store.remember(PLAYER, IP, T0);
        assertFalse(store.isValid(PLAYER, IP, T0.plus(Duration.ofMinutes(15))));
    }

    @Test
    void сессияПривязанаКАдресу() {
        // Без этого достаточно дождаться, пока игрок выйдет, и зайти под его
        // ником — пароль не спросят.
        SessionStore store = new SessionStore(Duration.ofMinutes(15));
        store.remember(PLAYER, IP, T0);
        assertFalse(store.isValid(PLAYER, "203.0.113.9", T0.plusSeconds(10)));
    }

    @Test
    void нулевоеОкноОзначаетПарольВсегда() {
        SessionStore store = new SessionStore(Duration.ZERO);
        store.remember(PLAYER, IP, T0);
        assertFalse(store.isValid(PLAYER, IP, T0));
    }

    @Test
    void переведённыеНазадЧасыНеПродлеваютСессию() {
        SessionStore store = new SessionStore(Duration.ofMinutes(15));
        store.remember(PLAYER, IP, T0);
        assertFalse(store.isValid(PLAYER, IP, T0.minus(Duration.ofHours(1))));
    }

    @Test
    void уборкаВыбрасываетТолькоПротухшие() {
        // Без уборки карта растёт весь аптайм сервера.
        SessionStore store = new SessionStore(Duration.ofMinutes(15));
        store.remember(PLAYER, IP, T0);
        store.remember(UUID.randomUUID(), IP, T0.plus(Duration.ofMinutes(14)));

        assertEquals(1, store.purgeExpired(T0.plus(Duration.ofMinutes(20))));
        assertEquals(1, store.size());
    }

    // ------------------------------------------------------ лимит попыток

    @Test
    void перваяПопыткаРазрешенаВсегда() {
        LoginThrottle throttle = new LoginThrottle(5, Duration.ofMinutes(5), Duration.ofMillis(250));
        assertTrue(throttle.check("Стив", T0).allowed());
    }

    @Test
    void слишкомБыстраяПовторнаяПопыткаОтклоняется() {
        LoginThrottle throttle = new LoginThrottle(5, Duration.ofMinutes(5), Duration.ofMillis(250));
        throttle.recordFailure("Стив", T0);

        LoginThrottle.Decision tooSoon = throttle.check("Стив", T0.plusMillis(100));
        assertFalse(tooSoon.allowed());
        assertFalse(tooSoon.lockedOut(), "это ещё не блокировка, а просто «слишком часто»");
        assertTrue(throttle.check("Стив", T0.plusMillis(300)).allowed());
    }

    @Test
    void сериаНеудачЗакрываетАккаунтНаВремя() {
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(5), Duration.ZERO);
        for (int i = 0; i < 3; i++) throttle.recordFailure("Стив", T0);

        LoginThrottle.Decision locked = throttle.check("Стив", T0.plusSeconds(1));
        assertFalse(locked.allowed());
        assertTrue(locked.lockedOut());
    }

    @Test
    void блокировкаСнимаетсяСамаИСчётНачинаетсяЗаново() {
        // Иначе первая же ошибка после разблокировки снова упирала бы в лимит,
        // и аккаунт оказался бы закрыт навсегда без вмешательства админа.
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(5), Duration.ZERO);
        for (int i = 0; i < 3; i++) throttle.recordFailure("Стив", T0);

        assertTrue(throttle.check("Стив", T0.plus(Duration.ofMinutes(6))).allowed());
        throttle.recordFailure("Стив", T0.plus(Duration.ofMinutes(6)));
        assertTrue(throttle.check("Стив", T0.plus(Duration.ofMinutes(7))).allowed());
    }

    @Test
    void успешныйВходОбнуляетСчётчик() {
        // Иначе накопленные за месяц случайные опечатки однажды закрыли бы
        // вход человеку, который всё это время исправно заходил.
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(5), Duration.ZERO);
        throttle.recordFailure("Стив", T0);
        throttle.recordFailure("Стив", T0);
        throttle.recordSuccess("Стив");

        throttle.recordFailure("Стив", T0);
        assertTrue(throttle.check("Стив", T0).allowed());
    }

    @Test
    void регистрНикаНеСоздаётВторойСчётчик() {
        LoginThrottle throttle = new LoginThrottle(2, Duration.ofMinutes(5), Duration.ZERO);
        throttle.recordFailure("Стив", T0);
        throttle.recordFailure("стив", T0);
        assertTrue(throttle.check("СТИВ", T0).lockedOut());
    }

    // ------------------------------------------------- отложенные сообщения

    @Test
    void сообщениеЗабираетсяРовноОдинРаз() {
        DeferredMessages<String> messages = new DeferredMessages<>();
        messages.hold(PLAYER, "Стив зашёл на сервер");

        assertEquals("Стив зашёл на сервер", messages.take(PLAYER).orElseThrow());
        assertTrue(messages.take(PLAYER).isEmpty());
    }

    @Test
    void ушедшийНеВойдяНеОставляетЗаписи() {
        // Иначе у каждого выкинутого по таймауту оставалась бы запись навсегда.
        DeferredMessages<String> messages = new DeferredMessages<>();
        messages.hold(PLAYER, "Стив зашёл на сервер");
        messages.drop(PLAYER);
        assertEquals(0, messages.size());
    }

    @Test
    void отсутствиеСообщенияНеХранится() {
        DeferredMessages<String> messages = new DeferredMessages<>();
        messages.hold(PLAYER, null);
        assertFalse(messages.isHolding(PLAYER));
    }

    // ------------------------------------------------------------- конфиг

    @Test
    void дефолтыРазумные() {
        AuthConfig config = AuthConfig.fromMap(Map.of());
        assertEquals(Duration.ofSeconds(60), config.loginTimeout());
        assertEquals(Duration.ofMinutes(15), config.sessionWindow());
        assertEquals(8, config.minPasswordLength());
        assertEquals(12, config.bcryptCost());
        assertEquals(AuthConfig.JoinMessageMode.DEFER, config.joinMessageMode());
    }

    @Test
    void значенияВнеДопустимогоЗажимаются() {
        AuthConfig config = AuthConfig.fromMap(Map.of(
                "login.timeout-seconds", 0,
                "login.bcrypt-cost", 30,
                "login.min-password-length", 1,
                "database.pool-size", 500));

        assertEquals(Duration.ofSeconds(10), config.loginTimeout());
        assertEquals(14, config.bcryptCost(), "стоимость выше 14 вешала бы вход даже асинхронно");
        assertEquals(4, config.minPasswordLength());
        assertEquals(32, config.poolSize());
    }

    @Test
    void имяТаблицыЧиститсяОтВсегоЛишнего() {
        // Имя таблицы подставляется в SQL как есть — параметром его сделать
        // нельзя. Опечатка в конфиге не должна становиться способом выполнить
        // произвольный запрос.
        assertEquals("auth_accounts", AuthConfig.tableName("auth_accounts; DROP TABLE users--"));
        assertEquals("auth_accounts", AuthConfig.tableName("  "));
    }

    @Test
    void режимСообщенийРазбираетсяИПадаетВDefer() {
        assertEquals(AuthConfig.JoinMessageMode.SUPPRESS,
                AuthConfig.fromMap(Map.of("join-messages.mode", "suppress")).joinMessageMode());
        assertEquals(AuthConfig.JoinMessageMode.IGNORE,
                AuthConfig.fromMap(Map.of("join-messages.mode", "IGNORE")).joinMessageMode());
        assertEquals(AuthConfig.JoinMessageMode.DEFER,
                AuthConfig.fromMap(Map.of("join-messages.mode", "чепуха")).joinMessageMode());
    }

    @Test
    void проверкаДлиныПароля() {
        AuthConfig config = AuthConfig.fromMap(Map.of("login.min-password-length", 8));
        assertEquals(null, config.validatePassword("достаточнодлинный"));
        assertTrue(config.validatePassword("корот").contains("короче"));
    }

    // ---------------------------------------------------- свои тексты

    @Test
    void своиСообщенияВыключеныПоУмолчанию() {
        // На сервере с EssentialsX этим занимается он, и два включённых
        // плагина дали бы два сообщения о входе подряд.
        MessageSettings messages = AuthConfig.fromMap(Map.of()).messages();
        assertFalse(messages.joinEnabled());
        assertFalse(messages.quitEnabled());
        assertFalse(messages.motdEnabled());
    }

    @Test
    void плейсхолдерыПодставляются() {
        assertEquals("Стив зашёл, онлайн 3/20",
                MessageSettings.apply("{player} зашёл, онлайн {online}/{max}",
                        Map.of("player", "Стив", "online", "3", "max", "20")));
    }

    @Test
    void никСФигурнымиСкобкамиНеЛомаетПодстановку() {
        // Замена идёт одним проходом по шаблону. Последовательными replace ник
        // вида «{online}» на следующем шаге превратился бы в число — мелочь,
        // которая всплывает раз в год и выглядит как мистика.
        assertEquals("{online} зашёл, онлайн 3",
                MessageSettings.apply("{player} зашёл, онлайн {online}",
                        Map.of("player", "{online}", "online", "3")));
    }

    @Test
    void неизвестныйПлейсхолдерОстаётсяВидимым() {
        // Так опечатку в конфиге видно сразу, а не «пропало слово».
        assertEquals("Привет, {nickname}",
                MessageSettings.apply("Привет, {nickname}", Map.of("player", "Стив")));
    }

    @Test
    void незакрытаяСкобкаНеРоняетПлагин() {
        assertEquals("Привет, {player", MessageSettings.apply("Привет, {player", Map.of("player", "Стив")));
        assertEquals("", MessageSettings.apply(null, Map.of()));
    }

    @Test
    void строкиПриветствияЧитаютсяСписком() {
        MessageSettings messages = AuthConfig.fromMap(Map.of(
                "messages.motd.enabled", true,
                "messages.motd.lines", java.util.List.of("первая", "вторая"))).messages();
        assertTrue(messages.motdEnabled());
        assertEquals(java.util.List.of("первая", "вторая"), messages.motdLines());
    }

    @Test
    void однаСтрокаВместоСпискаТожеПринимается() {
        // Обычная ошибка в YAML: написать строку там, где ожидается список.
        // Падать из-за неё не за чем.
        assertEquals(java.util.List.of("одна"),
                AuthConfig.fromMap(Map.of("messages.motd.lines", "одна")).messages().motdLines());
    }

    @Test
    void байпасПоПравуВыключенПоУмолчанию() {
        // На offline-сервере это опасная настройка: UUID там считается из
        // ника, значит вошедший под чужим ником получает и чужие права.
        assertFalse(AuthConfig.fromMap(Map.of()).permissionBypass());
    }
}
