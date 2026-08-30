package ovh.aurumgg.guilds.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Участник гильдии.
 *
 * Ник хранится рядом с UUID намеренно. UUID — единственный настоящий ключ, но
 * показать в панели список из тридцати шести шестнадцатеричных цифр нельзя, а
 * ходить за ником в Mojang на каждую строчку списка — тем более. Ник здесь
 * такой, каким игрок заходил в последний раз.
 *
 * @param uuid     ключ игрока
 * @param username ник на момент последнего входа
 * @param rank     ранг в гильдии
 * @param joinedAt когда вступил — по нему же выбирается наследник лидера
 */
public record GuildMember(UUID uuid, String username, GuildRank rank, Instant joinedAt) {

    public GuildMember withRank(GuildRank newRank) {
        return new GuildMember(uuid, username, newRank, joinedAt);
    }

    public GuildMember withUsername(String newUsername) {
        return new GuildMember(uuid, newUsername, rank, joinedAt);
    }
}
