package ovh.aurumgg.guilds.api;

import java.time.Instant;

/**
 * В какой гильдии состоит игрок и кем.
 *
 * Отдельный тип, а не пара «гильдия + ранг»: карточке игрока в панели нужны
 * ровно эти четыре поля, и отдавать ради них полный состав гильдии незачем.
 *
 * @param guildId   ключ гильдии
 * @param guildName имя гильдии
 * @param guildTag  тег гильдии
 * @param rank      ранг игрока
 * @param joinedAt  когда вступил
 */
public record GuildMembership(
        long guildId, String guildName, String guildTag, GuildRank rank, Instant joinedAt) {}
