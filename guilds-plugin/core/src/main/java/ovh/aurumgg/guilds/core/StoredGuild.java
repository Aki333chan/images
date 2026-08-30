package ovh.aurumgg.guilds.core;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ovh.aurumgg.guilds.api.GuildMember;
import ovh.aurumgg.guilds.api.GuildSettings;

/**
 * Гильдия так, как она лежит в базе.
 *
 * Отдельно от типов публичного API намеренно. {@code GuildSummary} и
 * {@code GuildDetail} — это то, что показывают снаружи, и их состав определяет
 * панель: в списке нужен счётчик участников и ник лидера, а не сами участники.
 * Здесь же ровно то, что есть в таблицах, — и когда состав того или другого
 * поменяется, поменяется он в одном месте, а не в обоих сразу.
 *
 * @param id        ключ; он же даёт имя группе в LuckPerms
 * @param name      имя, уникальное среди гильдий
 * @param tag       тег, он же суффикс
 * @param leader    UUID лидера
 * @param bank      баланс банка
 * @param createdAt когда создана
 * @param settings  настройки
 * @param members   состав, включая лидера
 */
public record StoredGuild(
        long id,
        String name,
        String tag,
        UUID leader,
        double bank,
        Instant createdAt,
        GuildSettings settings,
        List<GuildMember> members) {}
