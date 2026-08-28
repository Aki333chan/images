package ovh.aurumgg.auth.api;

import java.time.Instant;

/**
 * Одноразовый токен сброса пароля.
 *
 * @param username  ник, которому он выдан — токен привязан к аккаунту, чужим
 *                  им не воспользоваться
 * @param token     сам код; в открытом виде существует только здесь и в ответе
 *                  панели, в базе лежит только его хеш
 * @param expiresAt когда перестанет действовать
 */
public record ResetToken(String username, String token, Instant expiresAt) {}
