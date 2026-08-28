package ovh.aurumgg.auth.core.totp;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Одноразовые коды по времени (TOTP, RFC 6238) — то, что показывают Google
 * Authenticator, Aegis, 1Password и прочие.
 *
 * ПОЧЕМУ СВОЯ РЕАЛИЗАЦИЯ. Алгоритм — это HMAC-SHA1 от номера тридцатисекундного
 * интервала и взятие шести цифр по «динамическому смещению». Всё нужное
 * (Mac, SecretKeySpec) есть в JDK, а корректность проверяется эталонными
 * векторами из RFC — то есть это ровно тот случай, когда своя реализация
 * проверяема насквозь и не тянет за собой зависимость внутрь плагина.
 *
 * Тесты гоняют её на официальных векторах RFC 6238 (секрет
 * «12345678901234567890», отметки 59, 1111111109, 1111111111, 1234567890,
 * 2000000000, 20000000000). Совпадение с ними и означает, что коды сойдутся с
 * любым нормальным приложением.
 */
public final class Totp {

    /** Шаг времени. Тридцать секунд — то, что по умолчанию у всех приложений. */
    public static final Duration STEP = Duration.ofSeconds(30);

    /** Длина кода. Шесть цифр — то, что показывают приложения. */
    public static final int DIGITS = 6;

    /** Длина секрета: 160 бит, как в примерах RFC и как делает Google. */
    private static final int SECRET_BYTES = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** Новый случайный секрет в base32 — его игрок вводит в приложение. */
    public static String generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return Base32.encode(secret);
    }

    /** Номер текущего тридцатисекундного интервала. */
    public static long counter(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP.getSeconds());
    }

    /** Код для конкретного интервала. Публичный ради тестов на векторах RFC. */
    public static String code(byte[] secret, long counter, int digits) {
        byte[] message = new byte[8];
        long value = counter;
        for (int i = 7; i >= 0; i--) {
            message[i] = (byte) (value & 0xff);
            value >>>= 8;
        }

        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            hash = mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA1 обязателен для любой JVM, а ключ мы формируем сами.
            throw new IllegalStateException("HMAC-SHA1 недоступен", e);
        }

        // Динамическое смещение: младшие четыре бита последнего байта задают,
        // откуда брать четыре байта результата. Так описано в RFC 4226.
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);

        int modulo = (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", binary % modulo);
    }

    /**
     * Проверка кода с допуском на расхождение часов.
     *
     * ВОЗВРАЩАЕТ НОМЕР ИНТЕРВАЛА, А НЕ «ДА/НЕТ», и это принципиально. Код живёт
     * тридцать секунд, и подсмотренный через плечо (или подсказанный по
     * телефону мошеннику) он всё это время годен повторно. Чтобы такого не
     * было, вызывающий обязан запомнить использованный номер и не принимать
     * его во второй раз — а для этого номер нужно знать.
     *
     * @param window сколько интервалов допускается в каждую сторону: часы на
     *               телефоне игрока и на сервере расходятся всегда
     */
    public static OptionalLong verify(String secretBase32, String code, Instant now, int window) {
        String digitsOnly = code == null ? "" : code.replace(" ", "").trim();
        if (digitsOnly.length() != DIGITS || !digitsOnly.chars().allMatch(Character::isDigit)) {
            return OptionalLong.empty();
        }

        byte[] secret;
        try {
            secret = Base32.decode(secretBase32);
        } catch (IllegalArgumentException e) {
            return OptionalLong.empty();
        }
        if (secret.length == 0) return OptionalLong.empty();

        long current = counter(now);
        for (long offset = -window; offset <= window; offset++) {
            long candidate = current + offset;
            // Сравнение за постоянное время: коды короткие, но разница во
            // времени ответа по длине совпавшего префикса — бесплатная
            // подсказка подбирающему.
            if (constantTimeEquals(code(secret, candidate, DIGITS), digitsOnly)) {
                return OptionalLong.of(candidate);
            }
        }
        return OptionalLong.empty();
    }

    /**
     * Ссылка, которую понимают приложения-аутентификаторы.
     *
     * Формат otpauth:// — де-факто стандарт от Google. Игроку показывается и
     * она, и сам секрет: QR-код в чат не нарисовать, а секрет вводится руками.
     */
    public static String otpauthUri(String issuer, String account, String secretBase32) {
        return "otpauth://totp/" + encode(issuer) + ":" + encode(account)
                + "?secret=" + secretBase32
                + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP.getSeconds();
    }

    /** Секрет группами по четыре: так его реально переписать с экрана. */
    public static String readable(String secretBase32) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < secretBase32.length(); i += 4) {
            if (i > 0) result.append(' ');
            result.append(secretBase32, i, Math.min(i + 4, secretBase32.length()));
        }
        return result.toString();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
