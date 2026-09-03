package ovh.aurumgg.companion.core.model;

import java.util.UUID;

/**
 * Игрок, который когда-либо заходил на сервер.
 *
 * <h2>Откуда берётся</h2>
 *
 * Из {@code Bukkit.getOfflinePlayers()} — списка всех, кого сервер помнит.
 * Это ванильные данные: они есть всегда и ни от каких плагинов не зависят.
 *
 * <h2>Что здесь может отсутствовать и почему</h2>
 *
 * Три поля заполняются только при наличии соседних плагинов, и {@code null}
 * в них означает «неизвестно», а не «нет»:
 *
 * <ul>
 *   <li>{@code alias} — ник из EssentialsX. Без него поля не будет вовсе,
 *       как и у игрока, который ник себе не ставил;</li>
 *   <li>{@code registered} — есть ли аккаунт в AurumAuth. Без плагина
 *       авторизации делить список не на что, и панель показывает его целиком;
 *   </li>
 *   <li>{@code lastSeen} — 0, если сервер не помнит даты последнего входа.
 *       Так бывает у записей, попавших в кэш из белого списка.</li>
 * </ul>
 *
 * @param uuid       ключ игрока
 * @param name       настоящее игровое имя
 * @param alias      ник из EssentialsX или null
 * @param op         оператор ли — {@code isOp()}, ванильные данные
 * @param online     в сети ли прямо сейчас
 * @param registered есть ли аккаунт; null — плагина авторизации нет
 * @param lastSeen   когда заходил в последний раз, epoch ms; 0 — неизвестно
 */
public record KnownPlayer(
        UUID uuid,
        String name,
        String alias,
        boolean op,
        boolean online,
        Boolean registered,
        long lastSeen) {}
