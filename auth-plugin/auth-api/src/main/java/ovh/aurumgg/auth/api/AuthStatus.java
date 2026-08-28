package ovh.aurumgg.auth.api;

/** Состояние игрока, находящегося в сети. */
public enum AuthStatus {

    /** Подключился, но пароль ещё не ввёл: движение, чат и команды заблокированы. */
    AWAITING_LOGIN,

    /** Аккаунта нет — ждём /register. */
    AWAITING_REGISTRATION,

    /** Ввёл пароль в этой сессии. */
    AUTHENTICATED,

    /**
     * Пароль не спрашивали: сессия ещё жива после недавнего переподключения.
     *
     * Отдельное значение, а не просто AUTHENTICATED: для доступа к игре разницы
     * нет, но при разборе инцидента важно видеть, вводили пароль в этот раз или
     * пустили по сессии.
     */
    AUTHENTICATED_BY_SESSION,

    /**
     * Пароль не спрашивали: вход подтверждён вышестоящим звеном (прокси в
     * online-mode). См. {@link PremiumVerdict#PREMIUM_VERIFIED}.
     */
    AUTHENTICATED_BY_PREMIUM;

    /** Пускать ли игрока в игру. */
    public boolean isAuthenticated() {
        return this == AUTHENTICATED
                || this == AUTHENTICATED_BY_SESSION
                || this == AUTHENTICATED_BY_PREMIUM;
    }
}
