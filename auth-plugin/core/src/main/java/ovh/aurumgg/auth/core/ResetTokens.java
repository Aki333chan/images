package ovh.aurumgg.auth.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Генерация и хеширование токенов сброса пароля.
 *
 * ПОЧЕМУ В БАЗЕ ХРАНИТСЯ ХЕШ, А НЕ САМ ТОКЕН. Токен — это временный ключ от
 * чужого аккаунта. Дамп базы (бэкап, доступ на чтение у стороннего сервиса,
 * SQL-инъекция где-то ещё) не должен давать возможность войти под игроками,
 * которым за последние 20 минут выдали сброс.
 *
 * ПОЧЕМУ SHA-256, А НЕ BCRYPT. По токену нужно НАЙТИ запись, а не проверить
 * известную: с bcrypt (у которого своя соль на каждую запись) пришлось бы
 * перебирать всю таблицу. SHA-256 даёт индексируемый ключ. Обычно так делать
 * с паролями нельзя, но здесь другое: токен не выбирает человек, он случайный
 * из 31^8 вариантов и живёт минуты — подбирать по словарю нечего.
 */
public final class ResetTokens {

    /**
     * Алфавит без похожих символов: токен диктуют голосом и набирают в чат,
     * а 0/O и 1/I/l в этот момент неразличимы.
     */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /** Ровно восемь знаков — как договаривались, и достаточно при жизни в 20 минут. */
    public static final int LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ResetTokens() {}

    public static String generate() {
        StringBuilder builder = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    /**
     * Хеш для поиска в базе.
     *
     * Регистр приводится к верхнему до хеширования: игрок наберёт токен как
     * получится, и «abcd2345» должен находить ту же запись, что «ABCD2345».
     */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalize(token).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(Character.forDigit((b >> 4) & 0xf, 16))
                    .append(Character.forDigit(b & 0xf, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 обязателен для любой JVM — сюда попасть нельзя.
            throw new IllegalStateException("В этой JVM нет SHA-256", e);
        }
    }

    /** Токен как его набрал человек → канонический вид. */
    public static String normalize(String token) {
        return token == null ? "" : token.trim().toUpperCase(Locale.ROOT);
    }

    /** Похоже ли на токен вообще — чтобы отличить «/reset ТОКЕН» от «/reset пароль пароль». */
    public static boolean looksLikeToken(String value) {
        String normalized = normalize(value);
        if (normalized.length() != LENGTH) return false;
        for (int i = 0; i < normalized.length(); i++) {
            if (ALPHABET.indexOf(normalized.charAt(i)) < 0) return false;
        }
        return true;
    }
}
