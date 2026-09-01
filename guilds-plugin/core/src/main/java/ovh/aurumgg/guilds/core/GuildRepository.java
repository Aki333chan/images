package ovh.aurumgg.guilds.core;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ovh.aurumgg.guilds.api.BonusType;
import ovh.aurumgg.guilds.api.GuildBankEntry;
import ovh.aurumgg.guilds.api.GuildBonus;
import ovh.aurumgg.guilds.api.GuildRank;
import ovh.aurumgg.guilds.api.GuildSettings;

/**
 * Хранилище гильдий.
 *
 * Интерфейс нужен ровно затем, чтобы вся логика гильдий проверялась тестами
 * без MariaDB: в тестах его место занимает карта в памяти. Настоящая
 * реализация одна — {@link MariaDbGuildRepository}.
 *
 * Все методы БЛОКИРУЮЩИЕ и бросают проверяемые исключения: асинхронностью
 * занимается {@link GuildService}, у которого для этого есть свой пул. Прятать
 * потоки внутрь хранилища значило бы получить два разных места, где решается,
 * в каком потоке идёт работа.
 */
public interface GuildRepository extends AutoCloseable {

    void initSchema() throws Exception;

    /**
     * Все гильдии со всем составом.
     *
     * Читается один раз при старте: дальше гильдии живут в памяти
     * {@link GuildService}, а сюда уходят только изменения. Причина — HUD и
     * чат спрашивают «в какой гильдии этот игрок» десятки раз в секунду, и
     * поход в базу на каждый такой вопрос убил бы главный поток.
     */
    List<StoredGuild> loadAll() throws Exception;

    /** @return ключ созданной гильдии */
    long createGuild(
            String name, String tag, UUID leader, String leaderName, Instant createdAt,
            GuildSettings settings) throws Exception;

    void deleteGuild(long guildId) throws Exception;

    void updateTag(long guildId, String tag) throws Exception;

    void updateSettings(long guildId, GuildSettings settings) throws Exception;

    void updateLeader(long guildId, UUID leader) throws Exception;

    void updateBank(long guildId, double balance) throws Exception;

    void addMember(long guildId, UUID uuid, String username, GuildRank rank, Instant joinedAt)
            throws Exception;

    void removeMember(long guildId, UUID uuid) throws Exception;

    void updateRank(long guildId, UUID uuid, GuildRank rank) throws Exception;

    /**
     * Обновить ник участника.
     *
     * Ник в базе — это копия для показа, а не ключ. Игрок мог сменить его
     * между заходами, и без обновления панель показывала бы состав гильдии по
     * никам годичной давности.
     */
    void updateUsername(UUID uuid, String username) throws Exception;

    // ------------------------------------------------------------ бонусы

    /**
     * Все бонусы всех гильдий — читается один раз при старте, как и сами
     * гильдии. Дальше живут в памяти: величины спрашивают на каждом сломанном
     * блоке.
     */
    java.util.Map<Long, List<GuildBonus>> loadBonuses() throws Exception;

    /** Выдать или заменить бонус этого вида у гильдии. */
    void saveBonus(long guildId, GuildBonus bonus) throws Exception;

    /** Снять бонус. Тихо ничего не делает, если такого не было. */
    void deleteBonus(long guildId, BonusType type) throws Exception;

    // --------------------------------------------------- регионы WorldGuard

    /** Все привязки регионов к гильдиям — читается один раз при старте. */
    List<GuildRegion> loadRegions() throws Exception;

    void addRegion(GuildRegion region) throws Exception;

    void removeRegion(GuildRegion region) throws Exception;

    void logBank(GuildBankEntry entry) throws Exception;

    /** Операции с банком, новые сверху. */
    List<GuildBankEntry> bankHistory(long guildId, int limit) throws Exception;

    @Override
    void close();
}
