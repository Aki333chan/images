package ovh.aurumgg.guilds.core;

import java.util.List;
import ovh.aurumgg.guilds.api.GuildRank;

/**
 * Что показать в сайдбаре одному игроку.
 *
 * Отдельная структура, а не набор аргументов: она собирается в слое Bukkit
 * (там живут игроки и их здоровье), а превращается в строки в core — и ровно
 * поэтому превращение проверяется тестами без запуска сервера.
 *
 * @param partyMembers участники пати; пусто — пати нет
 * @param partyLimit   вместимость пати, для строки «3/8»
 * @param guildName    имя гильдии; null — игрок ни в какой не состоит
 * @param guildTag     тег гильдии
 * @param rank         ранг игрока в гильдии
 * @param guildOnline  сколько участников гильдии сейчас в сети
 * @param guildTotal   сколько всего участников
 * @param bankBalance  баланс банка; null — банка нет (нет Vault или он выключен)
 */
public record HudModel(
        List<Member> partyMembers,
        int partyLimit,
        String guildName,
        String guildTag,
        GuildRank rank,
        int guildOnline,
        int guildTotal,
        Double bankBalance) {

    /**
     * Строчка про одного участника пати.
     *
     * @param name          ник
     * @param healthPercent доля здоровья, 0..100
     * @param online        в сети ли
     * @param leader        лидер ли пати
     */
    public record Member(String name, double healthPercent, boolean online, boolean leader) {}

    public boolean hasParty() {
        return partyMembers != null && !partyMembers.isEmpty();
    }

    public boolean hasGuild() {
        return guildName != null && !guildName.isBlank();
    }

    /** Показывать ли сайдбар вообще: пустой показывать незачем. */
    public boolean isEmpty() {
        return !hasParty() && !hasGuild();
    }
}
