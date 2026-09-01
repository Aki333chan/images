package ovh.aurumgg.companion.core.model;

/**
 * Бонус гильдии так, как его отдают панели.
 *
 * Свой тип, а не тип из guilds-api, — по той же причине, что и {@link GuildInfo}:
 * модуль core companion собран без единой зависимости, и притащить сюда типы
 * AurumGuilds значило бы сделать companion несобираемым без плагина гильдий.
 *
 * @param type          вид: mining_speed, movement_speed, block_drops, mob_drops, experience
 * @param title         название вида по-русски — панели не нужно его знать самой
 * @param magnitude     величина: уровень эффекта или множитель
 * @param multiplier    true — величина это множитель, false — уровень эффекта
 * @param expiresAtEpochMs когда истекает; 0 — постоянный
 * @param grantedBy     кто выдал
 * @param grantedAtEpochMs когда выдали
 */
public record GuildBonusInfo(
        String type,
        String title,
        double magnitude,
        boolean multiplier,
        long expiresAtEpochMs,
        String grantedBy,
        long grantedAtEpochMs) {}
