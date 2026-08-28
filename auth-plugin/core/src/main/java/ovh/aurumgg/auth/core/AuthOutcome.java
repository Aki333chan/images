package ovh.aurumgg.auth.core;

import java.time.Duration;

/**
 * Чем закончилась попытка входа или регистрации.
 *
 * Текст сообщения формируется здесь, а не в слое Bukkit: так его видно рядом
 * с условием, при котором он возникает, и можно проверить тестом. Слой Bukkit
 * занимается только отправкой.
 */
public record AuthOutcome(Kind kind, String message) {

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

    static AuthOutcome ok(String message) {
        return new AuthOutcome(Kind.OK, message);
    }

    static AuthOutcome wrongPassword() {
        // Намеренно не уточняем, «пароль неверный» или «такого игрока нет»:
        // разница в формулировке подсказала бы подбирающему, какие ники
        // вообще зарегистрированы.
        return new AuthOutcome(Kind.WRONG_PASSWORD, "Неверный пароль");
    }

    static AuthOutcome notRegistered() {
        return new AuthOutcome(Kind.NOT_REGISTERED, "Вы ещё не зарегистрированы: /register <пароль> <пароль>");
    }

    static AuthOutcome alreadyRegistered() {
        return new AuthOutcome(Kind.ALREADY_REGISTERED, "Этот ник уже зарегистрирован: /login <пароль>");
    }

    static AuthOutcome throttled(Duration retryAfter) {
        long seconds = Math.max(1, retryAfter.toSeconds());
        return new AuthOutcome(Kind.THROTTLED, "Слишком часто. Попробуйте через " + seconds + " с");
    }

    static AuthOutcome locked(Duration retryAfter) {
        long minutes = Math.max(1, retryAfter.toMinutes());
        return new AuthOutcome(Kind.LOCKED,
                "Слишком много неудачных попыток. Вход закрыт на " + minutes + " мин");
    }

    static AuthOutcome badPassword(String reason) {
        return new AuthOutcome(Kind.BAD_PASSWORD, reason);
    }

    static AuthOutcome mismatch() {
        return new AuthOutcome(Kind.MISMATCH, "Пароли не совпадают");
    }

    static AuthOutcome resetTokenInvalid() {
        // Один и тот же текст на «не существует», «уже использован» и «истёк»:
        // по разнице между ними подбор восьми символов стал бы осмысленнее.
        return new AuthOutcome(Kind.RESET_TOKEN_INVALID, "Токен не подошёл или истёк");
    }

    static AuthOutcome resetReady() {
        return new AuthOutcome(Kind.RESET_READY,
                "Токен принят. Придумайте новый пароль: /reset <пароль> <пароль ещё раз>");
    }

    static AuthOutcome error() {
        // Подробности идут в лог сервера, а не игроку: текст ошибки БД
        // рассказывает постороннему больше, чем следует.
        return new AuthOutcome(Kind.ERROR, "Сервис авторизации недоступен, попробуйте позже");
    }
}
