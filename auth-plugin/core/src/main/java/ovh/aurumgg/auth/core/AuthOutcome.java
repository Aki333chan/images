package ovh.aurumgg.auth.core;

import java.time.Duration;
import java.util.Map;

/**
 * Чем закончилась попытка входа или регистрации.
 *
 * ЗДЕСЬ КЛЮЧ, А НЕ ГОТОВАЯ ФРАЗА. Раньше текст собирался прямо тут: так его
 * было видно рядом с условием, при котором он возникает. Но язык сервера
 * известен только слою Bukkit, который читает config.yml, — а исход входа
 * вычисляется в core, где ни конфига, ни игрока нет. Ключ решает обе задачи:
 * условие и его формулировка по-прежнему рядом (ключ говорящий и проверяется
 * тестом), а язык выбирается там, где он известен.
 *
 * Подстановки лежат отдельной картой: «через 5 с» и «через 5 s» отличаются не
 * только словом, и склеивать число со словом в core значило бы решать за
 * язык, в каком порядке они идут.
 */
public record AuthOutcome(Kind kind, String messageKey, Map<String, String> values) {

    public AuthOutcome(Kind kind, String messageKey) {
        this(kind, messageKey, Map.of());
    }

    public enum Kind {
        /** Вошёл или зарегистрировался. */
        OK,
        /** Пароль не подошёл. */
        WRONG_PASSWORD,
        /** Аккаунта нет — нужно /register. */
        NOT_REGISTERED,
        /** Аккаунт уже есть — нужно /login. */
        ALREADY_REGISTERED,
        /** Слишком часто: нужно немного подождать. */
        THROTTLED,
        /** Аккаунт временно закрыт после серии неудачных попыток. */
        LOCKED,
        /** Пароль не проходит по длине. */
        BAD_PASSWORD,
        /** Пароль и подтверждение не совпали. */
        MISMATCH,
        /** Пароль верный, нужен код двухфакторки. */
        TOTP_REQUIRED,
        /** Код двухфакторки не подошёл. */
        TOTP_INVALID,
        /** Токен сброса не подошёл: не тот, использован или истёк. */
        RESET_TOKEN_INVALID,
        /** Токен принят — ждём новый пароль. */
        RESET_READY,
        /** База недоступна или ответила ошибкой. */
        ERROR;

        public boolean isSuccess() {
            return this == OK;
        }
    }

    public boolean isSuccess() {
        return kind.isSuccess();
    }

    static AuthOutcome ok(String messageKey) {
        return new AuthOutcome(Kind.OK, messageKey);
    }

    static AuthOutcome wrongPassword() {
        // Намеренно не уточняем, «пароль неверный» или «такого игрока нет»:
        // разница в формулировке подсказала бы подбирающему, какие ники
        // вообще зарегистрированы.
        return new AuthOutcome(Kind.WRONG_PASSWORD, "auth.wrongPassword");
    }

    static AuthOutcome notRegistered() {
        return new AuthOutcome(Kind.NOT_REGISTERED, "auth.notRegistered");
    }

    static AuthOutcome alreadyRegistered() {
        return new AuthOutcome(Kind.ALREADY_REGISTERED, "auth.alreadyRegistered");
    }

    static AuthOutcome throttled(Duration retryAfter) {
        long seconds = Math.max(1, retryAfter.toSeconds());
        return new AuthOutcome(Kind.THROTTLED, "auth.throttled",
                Map.of("seconds", String.valueOf(seconds)));
    }

    static AuthOutcome locked(Duration retryAfter) {
        long minutes = Math.max(1, retryAfter.toMinutes());
        return new AuthOutcome(Kind.LOCKED, "auth.locked",
                Map.of("minutes", String.valueOf(minutes)));
    }

    static AuthOutcome badPassword(String reasonKey, Map<String, String> values) {
        return new AuthOutcome(Kind.BAD_PASSWORD, reasonKey, values);
    }

    static AuthOutcome mismatch() {
        return new AuthOutcome(Kind.MISMATCH, "auth.mismatch");
    }

    static AuthOutcome totpRequired() {
        return new AuthOutcome(Kind.TOTP_REQUIRED, "auth.totpRequired");
    }

    static AuthOutcome totpInvalid() {
        // Отдельно от «неверный пароль»: здесь скрывать нечего — пароль уже
        // подошёл, и человеку важно понимать, что не так именно с кодом.
        return new AuthOutcome(Kind.TOTP_INVALID, "auth.totpInvalid");
    }

    static AuthOutcome resetTokenInvalid() {
        // Один и тот же текст на «не существует», «уже использован» и «истёк»:
        // по разнице между ними подбор восьми символов стал бы осмысленнее.
        return new AuthOutcome(Kind.RESET_TOKEN_INVALID, "auth.resetTokenInvalid");
    }

    static AuthOutcome resetReady() {
        return new AuthOutcome(Kind.RESET_READY, "auth.resetReady");
    }

    static AuthOutcome error() {
        // Подробности идут в лог сервера, а не игроку: текст ошибки БД
        // рассказывает постороннему больше, чем следует.
        return new AuthOutcome(Kind.ERROR, "auth.serviceDown");
    }
}
