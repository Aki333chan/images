package ovh.aurumgg.guilds.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Мост к LuckPerms, который вместо LuckPerms просто записывает, о чём его
 * попросили.
 *
 * Нужен ради одной проверки, которую иначе не сделать: при смене тега суффикс
 * должен обновиться РОВНО ОДИН РАЗ, на группе гильдии, а не по разу на каждого
 * участника. Разница не видна в поведении, но на гильдии в полсотни человек это
 * пятьдесят обращений к чужому плагину вместо одного.
 */
final class RecordingHooks implements GuildHooks {

    final List<String> calls = new ArrayList<>();

    @Override
    public void guildCreated(long guildId, String tag) {
        calls.add("created " + guildId + " " + tag);
    }

    @Override
    public void guildDeleted(long guildId) {
        calls.add("deleted " + guildId);
    }

    @Override
    public void tagChanged(long guildId, String tag) {
        calls.add("tag " + guildId + " " + tag);
    }

    @Override
    public void memberJoined(long guildId, UUID player) {
        calls.add("joined " + guildId + " " + player);
    }

    @Override
    public void memberLeft(long guildId, UUID player) {
        calls.add("left " + guildId + " " + player);
    }

    long count(String prefix) {
        return calls.stream().filter(call -> call.startsWith(prefix)).count();
    }
}
