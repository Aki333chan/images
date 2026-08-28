package ovh.aurumgg.companion.core.webtoken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Одноразовые коды входа в панель.
 *
 * Проверяется в первую очередь то, что должно НЕ работать: повторное
 * использование, протухший код, чужой код. Ошибка здесь означает вход в панель
 * под чужим аккаунтом, и на экране она никак не проявляется.
 */
class WebTokenStoreTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-08-28T12:00:00Z");

    @Test
    void свежийКодОбмениваетсяНаИгрока() {
        WebTokenStore store = new WebTokenStore(Duration.ofMinutes(5));
        String code = store.issue(PLAYER, "Стив", T0);

        WebTokenStore.Issued issued = store.consume(code, T0.plusSeconds(30)).orElseThrow();
        assertEquals(PLAYER, issued.playerUuid());
        assertEquals("Стив", issued.username());
    }

    @Test
    void кодСрабатываетРовноОдинРаз() {
        // Подсмотренный через плечо код не должен работать после того, как им
        // уже воспользовались.
        WebTokenStore store = new WebTokenStore(Duration.ofMinutes(5));
        String code = store.issue(PLAYER, "Стив", T0);

        assertTrue(store.consume(code, T0).isPresent());
        assertTrue(store.consume(code, T0).isEmpty());
    }

    @Test
    void протухшийКодНеРаботает() {
        WebTokenStore store = new WebTokenStore(Duration.ofMinutes(5));
        String code = store.issue(PLAYER, "Стив", T0);
        assertTrue(store.consume(code, T0.plus(Duration.ofMinutes(5))).isEmpty());
    }

    @Test
    void новыйКодОтменяетПредыдущий() {
        // Два живых кода на одного игрока — это лишний шанс, что сработает
        // тот, который подсмотрели.
        WebTokenStore store = new WebTokenStore(Duration.ofMinutes(5));
        String first = store.issue(PLAYER, "Стив", T0);
        String second = store.issue(PLAYER, "Стив", T0);

        assertTrue(store.consume(first, T0).isEmpty());
        assertTrue(store.consume(second, T0).isPresent());
    }

    @Test
    void регистрВводаНеВажен() {
        WebTokenStore store = new WebTokenStore(Duration.ofMinutes(5));
        String code = store.issue(PLAYER, "Стив", T0);
        assertTrue(store.consume(" " + code.toLowerCase(java.util.Locale.ROOT) + " ", T0).isPresent());
    }

    @Test
    void несуществующийКодНеПускает() {
        WebTokenStore store = new WebTokenStore(Duration.ofMinutes(5));
        assertTrue(store.consume("ZZZZZZZZ", T0).isEmpty());
        assertTrue(store.consume(null, T0).isEmpty());
        assertTrue(store.consume("", T0).isEmpty());
    }

    @Test
    void переведённыеНазадЧасыНеОживляютКод() {
        WebTokenStore store = new WebTokenStore(Duration.ofMinutes(5));
        String code = store.issue(PLAYER, "Стив", T0);
        assertTrue(store.consume(code, T0.minus(Duration.ofHours(1))).isEmpty());
    }

    @Test
    void вКодеНетПохожихДругНаДругаСимволов() {
        // Код диктуют голосом и набирают с телефона: 0 и O, 1 и I в этот
        // момент неразличимы.
        WebTokenStore store = new WebTokenStore(Duration.ofMinutes(5));
        for (int i = 0; i < 200; i++) {
            String code = store.issue(UUID.randomUUID(), "Игрок", T0);
            assertFalse(code.matches(".*[01OIL].*"), "в коде похожие символы: " + code);
            assertEquals(8, code.length());
        }
    }

    @Test
    void кодыНеПовторяются() {
        WebTokenStore store = new WebTokenStore(Duration.ofMinutes(5));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertTrue(seen.add(store.issue(UUID.randomUUID(), "Игрок", T0)));
        }
        assertNotEquals(0, seen.size());
    }

    @Test
    void уборкаВыбрасываетТолькоПротухшие() {
        WebTokenStore store = new WebTokenStore(Duration.ofMinutes(5));
        store.issue(UUID.randomUUID(), "Старый", T0);
        store.issue(UUID.randomUUID(), "Свежий", T0.plus(Duration.ofMinutes(4)));

        assertEquals(1, store.purgeExpired(T0.plus(Duration.ofMinutes(6))));
        assertEquals(1, store.size());
    }
}
