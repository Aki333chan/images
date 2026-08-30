package ovh.aurumgg.guilds.api;

import java.util.List;
import java.util.UUID;

/**
 * Пати, как её видно снаружи.
 *
 * Пати живёт только в памяти и только пока в ней кто-то есть, поэтому у неё
 * нет ни ключа в базе, ни истории. Ключ — собственный id, а не UUID лидера:
 * лидер меняется при его выходе, а пати остаётся той же самой.
 *
 * @param id      ключ пати в пределах запуска сервера
 * @param leader  текущий лидер
 * @param members все участники, лидер первым, дальше по времени вступления
 */
public record PartyView(long id, UUID leader, List<UUID> members) {

    public int size() {
        return members.size();
    }
}
