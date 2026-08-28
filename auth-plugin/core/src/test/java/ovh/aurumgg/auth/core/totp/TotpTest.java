package ovh.aurumgg.auth.core.totp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Одноразовые коды по времени.
 *
 * ГЛАВНАЯ ПРОВЕРКА ЗДЕСЬ — эталонные векторы RFC 6238. Своя реализация TOTP
 * может ошибаться так, что коды выглядят абсолютно правдоподобно (шесть цифр,
 * меняются раз в полминуты) и при этом не совпадают ни с одним приложением.
 * Отладить такое на живом сервере, где «у меня код не подходит», практически
 * невозможно — поэтому совпадение с векторами проверяется до всего остального.
 */
class TotpTest {

    /** Секрет из RFC 6238: ASCII-строка «12345678901234567890». */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.UTF_8);
    private static final String RFC_SECRET_BASE32 = Base32.encode(RFC_SECRET);

    /** Отметка времени → ожидаемый восьмизначный код (столбец SHA-1 из RFC). */
    private static final long[][] RFC_TIMES = {
        {59L, 94287082L},
        {1111111109L, 7081804L},
        {1111111111L, 14050471L},
        {1234567890L, 89005924L},
        {2000000000L, 69279037L},
        {20000000000L, 65353130L},
    };

    @Test
    void совпадаетСЭталоннымиВекторамиRfc6238() {
        for (long[] testCase : RFC_TIMES) {
            long counter = Math.floorDiv(testCase[0], 30L);
            String expected = String.format("%08d", testCase[1]);
            assertEquals(expected, Totp.code(RFC_SECRET, counter, 8),
                    "не сходится на отметке " + testCase[0]);
        }
    }

    @Test
    void шестизначныйКодЭтоХвостВосьмизначного() {
        // Приложения показывают шесть цифр; они получаются тем же вычислением
        // с другим модулем, а не обрезанием — но численно совпадают с хвостом.
        for (long[] testCase : RFC_TIMES) {
            long counter = Math.floorDiv(testCase[0], 30L);
            String eight = Totp.code(RFC_SECRET, counter, 8);
            assertEquals(eight.substring(2), Totp.code(RFC_SECRET, counter, 6));
        }
    }

    @Test
    void номерИнтервалаСчитаетсяПоТридцатиСекундам() {
        assertEquals(0, Totp.counter(Instant.ofEpochSecond(0)));
        assertEquals(0, Totp.counter(Instant.ofEpochSecond(29)));
        assertEquals(1, Totp.counter(Instant.ofEpochSecond(30)));
        assertEquals(1, Totp.counter(Instant.ofEpochSecond(59)));
        assertEquals(2, Totp.counter(Instant.ofEpochSecond(60)));
    }

    // ------------------------------------------------------------ проверка

    /** Код, который приложение показало бы в этот момент. */
    private static String codeAt(Instant at) {
        return Totp.code(RFC_SECRET, Totp.counter(at), Totp.DIGITS);
    }

    @Test
    void верныйКодПринимается() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        OptionalLong matched = Totp.verify(RFC_SECRET_BASE32, codeAt(now), now, 1);
        assertTrue(matched.isPresent());
        assertEquals(Totp.counter(now), matched.getAsLong());
    }

    @Test
    void чужойКодНеПринимается() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        assertTrue(Totp.verify(RFC_SECRET_BASE32, "000000", now, 1).isEmpty());
    }

    @Test
    void допускНаРасхожденияЧасов() {
        // Часы на телефоне игрока и на сервере расходятся всегда. Без допуска
        // половина игроков просто не сможет войти.
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String previous = codeAt(now.minusSeconds(30));
        String next = codeAt(now.plusSeconds(30));

        assertTrue(Totp.verify(RFC_SECRET_BASE32, previous, now, 1).isPresent());
        assertTrue(Totp.verify(RFC_SECRET_BASE32, next, now, 1).isPresent());
    }

    @Test
    void слишкомСтарыйКодУжеНеГодится() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String longAgo = codeAt(now.minusSeconds(120));
        assertTrue(Totp.verify(RFC_SECRET_BASE32, longAgo, now, 1).isEmpty());
    }

    @Test
    void возвращаетсяНомерИнтервалаРадиЗащитыОтПовтора() {
        // Код живёт полминуты, и подсмотренный через плечо он всё это время
        // годен снова. Номер интервала нужен, чтобы вызывающий запомнил его и
        // второй раз не принял.
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long expected = Totp.counter(now.minusSeconds(30));
        assertEquals(expected,
                Totp.verify(RFC_SECRET_BASE32, codeAt(now.minusSeconds(30)), now, 1).getAsLong());
    }

    @Test
    void мусорВместоКодаНеРоняет() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        assertTrue(Totp.verify(RFC_SECRET_BASE32, null, now, 1).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET_BASE32, "", now, 1).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET_BASE32, "abcdef", now, 1).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET_BASE32, "12345", now, 1).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET_BASE32, "1234567", now, 1).isEmpty());
    }

    @Test
    void испорченныйСекретНеРоняет() {
        // В базе может оказаться что угодно: правка руками, битая миграция.
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        assertTrue(Totp.verify("не base32!", "123456", now, 1).isEmpty());
        assertTrue(Totp.verify("", "123456", now, 1).isEmpty());
    }

    // ------------------------------------------------------------- секрет

    @Test
    void секретыСлучайныеИПодходящейДлины() {
        String first = Totp.generateSecret();
        assertEquals(32, first.length(), "160 бит в base32 — это ровно 32 знака");
        assertNotEquals(first, Totp.generateSecret());
        assertTrue(first.chars().allMatch(c -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c) >= 0));
    }

    @Test
    void ссылкаДляПриложенияСобранаПравильно() {
        String uri = Totp.otpauthUri("Aurum", "Стив", "ABCDEFGH");
        assertTrue(uri.startsWith("otpauth://totp/Aurum:"), uri);
        assertTrue(uri.contains("secret=ABCDEFGH"), uri);
        assertTrue(uri.contains("issuer=Aurum"), uri);
        assertTrue(uri.contains("digits=6"), uri);
        assertTrue(uri.contains("period=30"), uri);
        // Кириллический ник обязан быть закодирован: иначе ссылка развалится.
        assertFalse(uri.contains("Стив"), uri);
    }

    @Test
    void секретПоказываетсяГруппамиПоЧетыре() {
        // Его переписывают с экрана руками, и сплошные тридцать два знака
        // переписать без ошибки почти невозможно.
        assertEquals("ABCD EFGH IJ", Totp.readable("ABCDEFGHIJ"));
    }

    // ------------------------------------------------------------- base32

    @Test
    void base32СходитсяСВекторамиRfc4648() {
        assertEquals("", Base32.encode(new byte[0]));
        assertEquals("MY", Base32.encode("f".getBytes(StandardCharsets.UTF_8)));
        assertEquals("MZXQ", Base32.encode("fo".getBytes(StandardCharsets.UTF_8)));
        assertEquals("MZXW6", Base32.encode("foo".getBytes(StandardCharsets.UTF_8)));
        assertEquals("MZXW6YQ", Base32.encode("foob".getBytes(StandardCharsets.UTF_8)));
        assertEquals("MZXW6YTB", Base32.encode("fooba".getBytes(StandardCharsets.UTF_8)));
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void base32ДекодируетОбратно() {
        for (String word : new String[] {"f", "fo", "foo", "foob", "fooba", "foobar"}) {
            byte[] bytes = word.getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(bytes, Base32.decode(Base32.encode(bytes)), word);
        }
    }

    @Test
    void секретВводитсяРукамиИПотомуРазборСнисходительный() {
        // Игрок перепишет его с пробелами, в нижнем регистре и, возможно, с
        // дефисами. Отвечать на это «неверный секрет» — недоразумение.
        byte[] expected = Base32.decode("MZXW6YTBOI");
        assertArrayEquals(expected, Base32.decode("mzxw 6ytb oi"));
        assertArrayEquals(expected, Base32.decode("MZXW-6YTB-OI"));
        assertArrayEquals(expected, Base32.decode("MZXW6YTBOI======"));
    }

    @Test
    void символНеИзАлфавитаОтклоняется() {
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("MZXW1"));
    }
}
