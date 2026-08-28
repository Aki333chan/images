package ovh.aurumgg.auth.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Базовая гигиена против подбора пароля.
 *
 * ЭТО НЕ АНТИБОТ И НЕ ПЫТАЕТСЯ ИМ БЫТЬ — от массового захода ботов защищает
 * сетевой уровень, и дублировать его здесь незачем. Здесь решается более
 * узкая задача: не дать перебирать пароль к КОНКРЕТНОМУ аккаунту тысячами
 * попыток в минуту. Две дешёвые меры:
 *
 * <ul>
 *   <li>минимальная пауза между попытками — снимает скорость перебора, почти
 *       не мешая живому человеку, который ошибся раз-другой;</li>
 *   <li>блокировка аккаунта на несколько минут после серии неудач.</li>
 * </ul>
 *
 * СЧЁТ ВЕДЁТСЯ ПО НИКУ, А НЕ ПО IP. Перебирают именно аккаунт, а адрес
 * подбирающему сменить куда проще, чем угадать пароль. Обратная сторона —
 * чужими неудачными попытками можно временно закрыть вход владельцу; поэтому
 * блокировка короткая (минуты) и снимается сама, а не требует администратора.
 */
public final class LoginThrottle {

    /**
     * Что делать с попыткой.
     *
     * @param allowed    можно ли проверять пароль прямо сейчас
     * @param retryAfter сколько ждать, если нельзя
     * @param lockedOut  причина отказа — блокировка (а не просто «слишком часто»)
     */
    public record Decision(boolean allowed, Duration retryAfter, boolean lockedOut) {
        static final Decision OK = new Decision(true, Duration.ZERO, false);
    }

    private final int maxAttempts;
    private final Duration lockout;
    private final Duration minDelay;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public LoginThrottle(int maxAttempts, Duration lockout, Duration minDelay) {
        this.maxAttempts = maxAttempts;
        this.lockout = lockout;
        this.minDelay = minDelay;
    }

    public Decision check(String username, Instant now) {
        State state = states.get(key(username));
        if (state == null) return Decision.OK;

        if (state.lockedUntil != null) {
            if (now.isBefore(state.lockedUntil)) {
                return new Decision(false, Duration.between(now, state.lockedUntil), true);
            }
            // Блокировка истекла — начинаем счёт заново. Иначе следующая же
            // ошибка снова упирала бы в лимит.
            states.remove(key(username));
            return Decision.OK;
        }

        if (state.lastAttempt != null && !minDelay.isZero()) {
            Duration since = Duration.between(state.lastAttempt, now);
            if (!since.isNegative() && since.compareTo(minDelay) < 0) {
                return new Decision(false, minDelay.minus(since), false);
            }
        }
        return Decision.OK;
    }

    /** Неудачная попытка: считаем и, если перебрали, закрываем на время. */
    public void recordFailure(String username, Instant now) {
        states.compute(key(username), (k, previous) -> {
            int attempts = (previous == null ? 0 : previous.attempts) + 1;
            Instant lockedUntil = attempts >= maxAttempts ? now.plus(lockout) : null;
            return new State(attempts, now, lockedUntil);
        });
    }

    /**
     * Успешный вход обнуляет счётчик.
     *
     * Без этого накопленные за неделю случайные опечатки однажды заблокировали
     * бы человека, который всё это время исправно заходил.
     */
    public void recordSuccess(String username) {
        states.remove(key(username));
    }

    /** Убрать записи, которые уже ни на что не влияют. */
    public int purgeExpired(Instant now) {
        int before = states.size();
        states.values().removeIf(state -> {
            Instant relevantUntil = state.lockedUntil != null
                    ? state.lockedUntil
                    : (state.lastAttempt == null ? null : state.lastAttempt.plus(lockout));
            return relevantUntil != null && !now.isBefore(relevantUntil);
        });
        return before - states.size();
    }

    public int size() {
        return states.size();
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private record State(int attempts, Instant lastAttempt, Instant lockedUntil) {}
}
