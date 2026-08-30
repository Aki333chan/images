package ovh.aurumgg.guilds.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Гильдия без состава — то, что нужно для списка.
 *
 * Отдельно от {@link GuildDetail}, потому что список гильдий в панели и в игре
 * запрашивается часто, а состав каждой из них там не нужен: тянуть сотню
 * участников ради строчки «Драконы [DRG], 12 человек» значило бы делать
 * лишнюю работу на каждый показ списка.
 *
 * @param id          внутренний ключ; он же даёт имя группе в LuckPerms
 * @param name        отображаемое имя, уникальное среди гильдий
 * @param tag         тег, он же суффикс к нику; уникален и короток
 * @param leaderUuid  лидер
 * @param leaderName  ник лидера
 * @param memberCount сколько всего участников, включая лидера
 * @param bankBalance баланс банка; ноль, если Vault на сервере нет
 * @param createdAt   когда создана
 */
public record GuildSummary(
        long id,
        String name,
        String tag,
        UUID leaderUuid,
        String leaderName,
        int memberCount,
        double bankBalance,
        Instant createdAt) {}
