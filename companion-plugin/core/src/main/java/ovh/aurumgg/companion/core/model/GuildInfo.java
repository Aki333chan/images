package ovh.aurumgg.companion.core.model;

import java.util.List;

/**
 * Гильдия так, как её отдают панели.
 *
 * ПОЧЕМУ ЭТО СВОЙ ТИП, А НЕ ТИП ИЗ guilds-api. Модуль core companion собран
 * без единой зависимости — на этом держится и его сборка где угодно, и его
 * тесты без Bukkit. Типы AurumGuilds живут в его собственном артефакте, и
 * притащить их сюда значило бы сделать companion несобираемым без плагина
 * гильдий, то есть ровно то, чего мы избегаем. Перекладывает одно в другое
 * мост в слое Bukkit — там, где плагин гильдий и так может отсутствовать.
 *
 * @param id          ключ гильдии
 * @param name        имя
 * @param tag         тег, он же суффикс к нику
 * @param leaderUuid  UUID лидера
 * @param leaderName  ник лидера
 * @param memberCount сколько всего участников
 * @param bankBalance баланс общака; 0, если банк недоступен
 * @param createdAtEpochMs когда создана
 * @param members     состав; пусто в списке гильдий, заполнен в карточке
 */
public record GuildInfo(
        long id,
        String name,
        String tag,
        String leaderUuid,
        String leaderName,
        int memberCount,
        double bankBalance,
        long createdAtEpochMs,
        List<Member> members) {

    /**
     * Участник гильдии.
     *
     * @param uuid     ключ игрока
     * @param name     ник на момент последнего входа
     * @param rank     leader, officer или member
     * @param joinedAtEpochMs когда вступил
     */
    public record Member(String uuid, String name, String rank, long joinedAtEpochMs) {}
}
