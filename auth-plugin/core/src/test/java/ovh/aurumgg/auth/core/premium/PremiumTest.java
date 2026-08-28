package ovh.aurumgg.auth.core.premium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.auth.api.PremiumVerdict;

/**
 * Определение premium-игрока.
 *
 * Здесь решается, спрашивать ли пароль вообще, поэтому проверяется в первую
 * очередь то, что должно НЕ пускать: чужой ник, недоступный Mojang, лимит
 * запросов. Ошибка в любую из этих сторон не видна на экране — она видна
 * только тогда, когда под чужим ником уже зашли.
 */
class PremiumTest {

    private static final UUID REAL = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String BODY =
            "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}";

    // ------------------------------------------------------- разбор ответа

    @Test
    void профильРазбираетсяИUuidПолучаетДефисы() {
        MojangProfile profile = MojangProfile.parse(200, BODY).orElseThrow();
        assertEquals(REAL, profile.uuid());
        assertEquals("Notch", profile.name());
    }

    @Test
    void учёткиНетПри404() {
        assertTrue(MojangProfile.parse(404, "{\"error\":\"Not Found\"}").isEmpty());
    }

    @Test
    void учёткиНетПри204() {
        // Исторический ответ Mojang на неизвестный ник — пустой 204. Встретить
        // можно оба варианта, и оба означают одно и то же.
        assertTrue(MojangProfile.parse(204, "").isEmpty());
    }

    @Test
    void лимитЗапросовЭтоНеОтсутствиеУчётки() {
        // Самая опасная из возможных путаниц: 429 значит «спросить не удалось».
        // Принять его за «учётки нет» — это молча поменять поведение входа
        // ровно в тот момент, когда Mojang нас притормозил.
        assertThrows(IllegalStateException.class, () -> MojangProfile.parse(429, "slow down"));
        assertThrows(IllegalStateException.class, () -> MojangProfile.parse(503, ""));
    }

    @Test
    void двестиБезIdЭтоНепонятныйОтвет() {
        assertThrows(IllegalStateException.class, () -> MojangProfile.parse(200, "{\"name\":\"Notch\"}"));
    }

    // ------------------------------------------------------------- вердикт

    @Test
    void совпавшийUuidЗначитПроверкуСделалиВыше() {
        assertEquals(PremiumVerdict.PREMIUM_VERIFIED,
                PremiumChecker.decide(REAL, "Notch", new MojangProfile(REAL, "Notch")));
    }

    @Test
    void чужойНикСOfflineUuidПарольНеОтменяет() {
        // Сердцевина всей честности этой части: лицензия с таким ником есть,
        // но подключение пришло с offline-UUID — значит, это может быть кто
        // угодно, и пароль обязателен.
        UUID offline = PremiumChecker.offlineUuid("Notch");
        assertNotEquals(REAL, offline);
        assertEquals(PremiumVerdict.PREMIUM_NAME_ONLY,
                PremiumChecker.decide(offline, "Notch", new MojangProfile(REAL, "Notch")));
        assertTrue(!PremiumChecker.decide(offline, "Notch", new MojangProfile(REAL, "Notch"))
                .allowsPasswordBypass());
    }

    @Test
    void безЛицензииЭтоОбычныйОффлайнИгрок() {
        assertEquals(PremiumVerdict.OFFLINE_NAME,
                PremiumChecker.decide(PremiumChecker.offlineUuid("Вася"), "Вася", null));
    }

    @Test
    void offlineUuidСчитаетсяПоФормулеСервера() {
        // Формула ванильного LoginListener: UUID версии 3 от
        // "OfflinePlayer:" + ник. Если её сломать, совпадение с настоящим
        // UUID начнёт срабатывать не там, где надо.
        UUID offline = PremiumChecker.offlineUuid("Notch");
        assertEquals(3, offline.version());
        assertEquals(UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"), offline);
    }

    // ---------------------------------------------------------- поведение

    @Test
    void недоступныйMojangНеПускаетБезПароля() {
        PremiumChecker checker = new PremiumChecker(name -> {
            throw new java.io.IOException("сеть лежит");
        }, true, Duration.ofMinutes(60));

        assertEquals(PremiumVerdict.UNKNOWN, checker.check(REAL, "Notch"));
        assertTrue(!PremiumVerdict.UNKNOWN.allowsPasswordBypass());
    }

    @Test
    void неудачаНеКэшируется() {
        // Иначе минута сетевых проблем на час закрепила бы «не premium» за
        // всеми, кто заходил в это время.
        AtomicInteger calls = new AtomicInteger();
        PremiumChecker checker = new PremiumChecker(name -> {
            if (calls.incrementAndGet() == 1) throw new java.io.IOException("первый раз мимо");
            return Optional.of(new MojangProfile(REAL, "Notch"));
        }, true, Duration.ofMinutes(60));

        assertEquals(PremiumVerdict.UNKNOWN, checker.check(REAL, "Notch"));
        assertEquals(PremiumVerdict.PREMIUM_VERIFIED, checker.check(REAL, "Notch"));
        assertEquals(2, calls.get());
    }

    @Test
    void успешныйОтветКэшируется() {
        AtomicInteger calls = new AtomicInteger();
        PremiumChecker checker = new PremiumChecker(name -> {
            calls.incrementAndGet();
            return Optional.of(new MojangProfile(REAL, "Notch"));
        }, true, Duration.ofMinutes(60));

        checker.check(REAL, "Notch");
        checker.check(REAL, "notch");
        assertEquals(1, calls.get(), "регистр ника не должен создавать вторую запись в кэше");
    }

    @Test
    void выключеннаяПроверкаНеХодитВСеть() {
        AtomicInteger calls = new AtomicInteger();
        PremiumChecker checker = new PremiumChecker(name -> {
            calls.incrementAndGet();
            return Optional.of(new MojangProfile(REAL, "Notch"));
        }, false, Duration.ofMinutes(60));

        assertEquals(PremiumVerdict.UNKNOWN, checker.check(REAL, "Notch"));
        assertEquals(0, calls.get());
    }
}
