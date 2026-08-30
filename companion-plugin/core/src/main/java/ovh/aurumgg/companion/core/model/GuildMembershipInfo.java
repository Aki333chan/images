package ovh.aurumgg.companion.core.model;

/**
 * В какой гильдии состоит игрок — для его карточки в панели.
 *
 * @param guildId   ключ гильдии
 * @param guildName имя
 * @param guildTag  тег
 * @param rank      leader, officer или member
 * @param joinedAtEpochMs когда вступил
 */
public record GuildMembershipInfo(
        long guildId, String guildName, String guildTag, String rank, long joinedAtEpochMs) {}
