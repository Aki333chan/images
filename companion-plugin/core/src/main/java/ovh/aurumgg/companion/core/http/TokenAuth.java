package ovh.aurumgg.companion.core.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Проверка статического токена из заголовка Authorization. */
public final class TokenAuth {

    private final byte[] expected;

    public TokenAuth(String token) {
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Принимает «Bearer <token>» и голый токен. Сравнение постоянного времени —
     * иначе по времени ответа секрет можно подобрать посимвольно.
     */
    public boolean isAuthorized(String authorizationHeader) {
        if (authorizationHeader == null) return false;
        String candidate = authorizationHeader.strip();
        if (candidate.regionMatches(true, 0, "Bearer ", 0, 7)) {
            candidate = candidate.substring(7).strip();
        }
        if (candidate.isEmpty()) return false;
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), expected);
    }
}
