package ovh.aurumgg.guilds.api;

import java.time.Duration;
import java.time.Instant;

/**
 * Действующий бонус гильдии.
 *
 * <h2>Постоянный и временный — одно и то же поле</h2>
 *
 * {@code expiresAt == null} означает «навсегда». Отдельного флага нет
 * намеренно: флаг и дата рано или поздно разойдутся — постоянный бонус с
 * проставленной датой или временный без неё, — и оба случая читаются как
 * ошибка данных, которую некому заметить.
 *
 * <h2>Один бонус каждого вида на гильдию</h2>
 *
 * Складывать два множителя добычи или два уровня «Спешки» не нужно: это
 * превращается в гонку, где выгоднее купить пять слабых бонусов вместо одного
 * сильного. Повторная выдача заменяет прежний — с новой величиной и новым
 * сроком.
 *
 * @param type      что усиливает
 * @param magnitude величина: уровень эффекта или множитель, смотря по типу
 * @param expiresAt когда перестанет действовать; null — навсегда
 * @param grantedBy кто выдал — ник администратора, «панель» или имя плагина
 * @param grantedAt когда выдали
 */
public record GuildBonus(
        BonusType type,
        double magnitude,
        Instant expiresAt,
        String grantedBy,
        Instant grantedAt) {

    public boolean permanent() {
        return expiresAt == null;
    }

    /** Истёк ли к этому моменту. Постоянный не истекает никогда. */
    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /** Сколько осталось; null — бонус постоянный. */
    public Duration remaining(Instant now) {
        if (expiresAt == null) return null;
        Duration left = Duration.between(now, expiresAt);
        return left.isNegative() ? Duration.ZERO : left;
    }
}
