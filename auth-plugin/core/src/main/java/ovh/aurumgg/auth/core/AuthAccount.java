package ovh.aurumgg.auth.core;

import java.time.Instant;
import java.util.UUID;

/**
 * Аккаунт из таблицы авторизации.
 *
 * @param uuid         UUID игрока — первичный ключ. Именно он, а не ник: ник
 *                     на смешанном сервере может смениться, UUID нет
 * @param username     ник в том написании, в котором игрок зарегистрировался
 * @param passwordHash bcrypt-хеш вместе с солью и стоимостью внутри строки
 * @param email        задел на восстановление пароля письмом; сейчас всегда
 *                     null и нигде не используется — поле заведено, чтобы
 *                     потом не мигрировать живую таблицу
 * @param registeredAt когда зарегистрировался
 * @param lastLoginAt  когда входил в последний раз (null у только что
 *                     зарегистрированного)
 * @param lastIp       адрес последнего входа: по нему проверяется сессия
 */
public record AuthAccount(
        UUID uuid,
        String username,
        String passwordHash,
        String email,
        Instant registeredAt,
        Instant lastLoginAt,
        String lastIp) {}
