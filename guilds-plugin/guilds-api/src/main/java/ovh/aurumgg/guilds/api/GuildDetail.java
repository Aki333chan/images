package ovh.aurumgg.guilds.api;

import java.util.List;

/**
 * Гильдия целиком: карточка, состав и настройки.
 *
 * @param summary  то же, что в списке
 * @param members  весь состав, лидер первым, дальше по старшинству и времени вступления
 * @param settings настройки гильдии
 */
public record GuildDetail(GuildSummary summary, List<GuildMember> members, GuildSettings settings) {}
