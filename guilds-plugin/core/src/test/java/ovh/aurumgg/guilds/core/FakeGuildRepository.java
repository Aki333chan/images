package ovh.aurumgg.guilds.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import ovh.aurumgg.guilds.api.BonusType;
import ovh.aurumgg.guilds.api.GuildBankEntry;
import ovh.aurumgg.guilds.api.GuildBonus;
import ovh.aurumgg.guilds.api.GuildMember;
import ovh.aurumgg.guilds.api.GuildRank;
import ovh.aurumgg.guilds.api.GuildSettings;

/**
 * Хранилище в памяти вместо MariaDB.
 *
 * Повторяет ровно те свойства настоящего, от которых зависит логика: имя и тег
 * уникальны (в MariaDB это делают UNIQUE-ключи), удаление гильдии уносит её
 * состав (там это ON DELETE CASCADE), а лог банка переживает роспуск.
 * Остальное — обычная карта.
 */
final class FakeGuildRepository implements GuildRepository {

    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, StoredGuild> guilds = new HashMap<>();
    private final List<GuildBankEntry> bankLog = new ArrayList<>();
    /** Бонусы: id гильдии → вид → бонус. Вложенная карта даёт «один вида на гильдию». */
    final Map<Long, Map<BonusType, GuildBonus>> bonuses = new HashMap<>();
    /** Сколько раз просили записать что-либо — чтобы отличить «не сохранилось». */
    int writes;

    @Override
    public void initSchema() {}

    @Override
    public List<StoredGuild> loadAll() {
        return List.copyOf(guilds.values());
    }

    @Override
    public long createGuild(
            String name, String tag, UUID leader, String leaderName, Instant createdAt,
            GuildSettings settings) {
        writes++;
        boolean taken = guilds.values().stream().anyMatch(guild ->
                guild.name().equalsIgnoreCase(name) || guild.tag().equalsIgnoreCase(tag));
        if (taken) throw new IllegalStateException("имя или тег заняты");

        long id = nextId.getAndIncrement();
        guilds.put(id, new StoredGuild(id, name, tag, leader, 0, createdAt, settings,
                List.of(new GuildMember(leader, leaderName, GuildRank.LEADER, createdAt))));
        return id;
    }

    @Override
    public void deleteGuild(long guildId) {
        writes++;
        // Состав уносится вместе с гильдией — как каскад в MariaDB.
        guilds.remove(guildId);
    }

    @Override
    public void updateTag(long guildId, String tag) {
        writes++;
        edit(guildId, guild -> new StoredGuild(guild.id(), guild.name(), tag, guild.leader(),
                guild.bank(), guild.createdAt(), guild.settings(), guild.members()));
    }

    @Override
    public void updateSettings(long guildId, GuildSettings settings) {
        writes++;
        edit(guildId, guild -> new StoredGuild(guild.id(), guild.name(), guild.tag(), guild.leader(),
                guild.bank(), guild.createdAt(), settings, guild.members()));
    }

    @Override
    public void updateLeader(long guildId, UUID leader) {
        writes++;
        edit(guildId, guild -> new StoredGuild(guild.id(), guild.name(), guild.tag(), leader,
                guild.bank(), guild.createdAt(), guild.settings(), guild.members()));
    }

    @Override
    public void updateBank(long guildId, double balance) {
        writes++;
        edit(guildId, guild -> new StoredGuild(guild.id(), guild.name(), guild.tag(), guild.leader(),
                balance, guild.createdAt(), guild.settings(), guild.members()));
    }

    @Override
    public void addMember(long guildId, UUID uuid, String username, GuildRank rank, Instant joinedAt) {
        writes++;
        edit(guildId, guild -> {
            List<GuildMember> members = new ArrayList<>(guild.members());
            members.removeIf(member -> member.uuid().equals(uuid));
            members.add(new GuildMember(uuid, username, rank, joinedAt));
            return withMembers(guild, members);
        });
    }

    @Override
    public void removeMember(long guildId, UUID uuid) {
        writes++;
        edit(guildId, guild -> withMembers(guild, guild.members().stream()
                .filter(member -> !member.uuid().equals(uuid))
                .toList()));
    }

    @Override
    public void updateRank(long guildId, UUID uuid, GuildRank rank) {
        writes++;
        edit(guildId, guild -> withMembers(guild, guild.members().stream()
                .map(member -> member.uuid().equals(uuid) ? member.withRank(rank) : member)
                .toList()));
    }

    @Override
    public void updateUsername(UUID uuid, String username) {
        writes++;
        for (Long id : List.copyOf(guilds.keySet())) {
            edit(id, guild -> withMembers(guild, guild.members().stream()
                    .map(member -> member.uuid().equals(uuid) ? member.withUsername(username) : member)
                    .toList()));
        }
    }

    @Override
    public Map<Long, List<GuildBonus>> loadBonuses() {
        Map<Long, List<GuildBonus>> result = new HashMap<>();
        bonuses.forEach((guildId, byType) -> result.put(guildId, new ArrayList<>(byType.values())));
        return result;
    }

    @Override
    public void saveBonus(long guildId, GuildBonus bonus) {
        writes++;
        bonuses.computeIfAbsent(guildId, key -> new HashMap<>()).put(bonus.type(), bonus);
    }

    @Override
    public void deleteBonus(long guildId, BonusType type) {
        writes++;
        Map<BonusType, GuildBonus> byType = bonuses.get(guildId);
        if (byType != null) byType.remove(type);
    }

    @Override
    public void logBank(GuildBankEntry entry) {
        writes++;
        bankLog.add(entry);
    }

    @Override
    public List<GuildBankEntry> bankHistory(long guildId, int limit) {
        List<GuildBankEntry> result = new ArrayList<>(bankLog.stream()
                .filter(entry -> entry.guildId() == guildId)
                .toList());
        java.util.Collections.reverse(result);
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    @Override
    public void close() {}

    /** Заглянуть в «базу» из теста, минуя кэш сервиса. */
    java.util.Optional<StoredGuild> peek(long guildId) {
        return java.util.Optional.ofNullable(guilds.get(guildId));
    }

    List<GuildBankEntry> allBankEntries() {
        return List.copyOf(bankLog);
    }

    private void edit(long guildId, java.util.function.UnaryOperator<StoredGuild> change) {
        StoredGuild guild = guilds.get(guildId);
        if (guild != null) guilds.put(guildId, change.apply(guild));
    }

    private static StoredGuild withMembers(StoredGuild guild, List<GuildMember> members) {
        return new StoredGuild(guild.id(), guild.name(), guild.tag(), guild.leader(), guild.bank(),
                guild.createdAt(), guild.settings(), List.copyOf(members));
    }
}
