package ovh.aurumgg.companion.core.model;

/**
 * Выданный игроку токен сброса пароля.
 *
 * @param username        ник, которому выдан токен
 * @param token           сам токен; существует в открытом виде только по пути
 *                        от плагина авторизации до панели и нигде не хранится
 * @param expiresAtEpochMs когда перестанет действовать
 */
public record PasswordReset(String username, String token, long expiresAtEpochMs) {}
