package ovh.aurumgg.guilds.paper;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.SuffixNode;
import ovh.aurumgg.guilds.core.GuildHooks;
import ovh.aurumgg.guilds.core.GuildNames;

/**
 * Суффикс с тегом гильдии — через LuckPerms, полностью автоматически.
 *
 * <h2>Как это устроено и почему именно так</h2>
 *
 * <ol>
 *   <li>у каждой гильдии заводится <b>своя группа</b> с техническим именем
 *       вида {@code guild_17}. Имя собирается из внутреннего id, а НЕ из тега:
 *       тег меняется и содержит что угодно, вплоть до кириллицы, а имя группы
 *       в LuckPerms обязано быть постоянным и безопасным;</li>
 *   <li>на группу вешается <b>одна</b> meta-нода suffix со значением тега;</li>
 *   <li>участнику добавляется <b>наследование</b> от этой группы. Суффикс
 *       приходит ему сам — прописывать suffix каждому пользователю по
 *       отдельности не нужно ни разу;</li>
 *   <li>при смене тега правится <b>только нода на группе</b>. Изменение
 *       доезжает до всех участников через то же наследование. На гильдии в
 *       полсотни человек это одно обращение к LuckPerms вместо пятидесяти;</li>
 *   <li>при роспуске группа удаляется целиком.</li>
 * </ol>
 *
 * Ни игроки, ни лидеры никаких команд LuckPerms не вводят и знать о нём не
 * обязаны.
 *
 * <h2>Зависимость мягкая</h2>
 *
 * Классы {@code net.luckperms.*} упоминаются ТОЛЬКО в этом файле. Создаётся он
 * лишь после проверки {@link #installed()}, которая до этих классов не
 * дотрагивается. Поэтому на сервере без LuckPerms класс просто никогда не
 * загружается, NoClassDefFoundError не возникает, а вместо моста работает
 * {@link GuildHooks#noop()} — гильдии живут полностью, просто без суффиксов.
 *
 * <h2>Ошибки не отменяют действие</h2>
 *
 * Если LuckPerms не ответил, вступление в гильдию всё равно состоялось.
 * Гильдия важнее суффикса, и откатывать её из-за чужого плагина было бы
 * худшим из возможных решений. Ошибка пишется в лог.
 */
final class LuckPermsBridge implements GuildHooks {

    static final String PLUGIN_NAME = "LuckPerms";

    /**
     * Приоритет суффикса.
     *
     * Не ноль: у сервера почти наверняка уже есть суффиксы от других групп, и
     * ноль означал бы «как повезёт». Сотня ставит тег гильдии выше обычных
     * донатных суффиксов, но оставляет запас для тех, кто захочет перебить его
     * осознанно.
     */
    private static final int SUFFIX_PRIORITY = 100;

    /** Операции идут в хранилище LuckPerms — ждём, но не вечно. */
    private static final long TIMEOUT_SECONDS = 5;

    // Не final: /guild admin reload их меняет.
    private volatile String groupPrefix;
    private volatile String suffixFormat;
    private final Logger logger;

    /** Применить перечитанный config.yml. */
    void applyConfig(String groupPrefix, String suffixFormat) {
        this.groupPrefix = groupPrefix;
        this.suffixFormat = suffixFormat;
    }

    LuckPermsBridge(String groupPrefix, String suffixFormat, Logger logger) {
        this.groupPrefix = groupPrefix;
        this.suffixFormat = suffixFormat;
        this.logger = logger;
    }

    /**
     * Есть ли LuckPerms.
     *
     * Проверка через PluginManager идёт ПЕРВОЙ и намеренно: без неё обращение
     * к LuckPermsProvider на сервере без LuckPerms уронило бы поток
     * NoClassDefFoundError вместо честного «нет».
     */
    static boolean installed() {
        if (org.bukkit.Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) == null) return false;
        try {
            LuckPermsProvider.get();
            return true;
        } catch (IllegalStateException | NoClassDefFoundError e) {
            return false;
        }
    }

    @Override
    public void guildCreated(long guildId, String tag) {
        LuckPerms api = api();
        if (api == null) return;
        String name = GuildNames.groupName(groupPrefix, guildId);
        run(api.getGroupManager().createAndLoadGroup(name)
                        .thenCompose(group -> applySuffix(api, group, tag)),
                "завести группу " + name);
    }

    @Override
    public void guildDeleted(long guildId) {
        LuckPerms api = api();
        if (api == null) return;
        String name = GuildNames.groupName(groupPrefix, guildId);
        run(api.getGroupManager().loadGroup(name).thenCompose(group -> group
                        .map(loaded -> api.getGroupManager().deleteGroup(loaded))
                        .orElseGet(() -> CompletableFuture.completedFuture(null))),
                "удалить группу " + name);
    }

    @Override
    public void tagChanged(long guildId, String tag) {
        LuckPerms api = api();
        if (api == null) return;
        String name = GuildNames.groupName(groupPrefix, guildId);
        // Один запрос на всю гильдию: участников по отдельности трогать не
        // нужно, суффикс придёт им через наследование.
        run(api.getGroupManager().loadGroup(name).thenCompose(group -> group
                        .map(loaded -> applySuffix(api, loaded, tag))
                        .orElseGet(() -> CompletableFuture.completedFuture(null))),
                "обновить суффикс группы " + name);
    }

    @Override
    public void memberJoined(long guildId, UUID player) {
        LuckPerms api = api();
        if (api == null) return;
        String name = GuildNames.groupName(groupPrefix, guildId);
        run(api.getUserManager().modifyUser(player, user ->
                        user.data().add(InheritanceNode.builder(name).build())),
                "добавить игрока в группу " + name);
    }

    @Override
    public void memberLeft(long guildId, UUID player) {
        LuckPerms api = api();
        if (api == null) return;
        String name = GuildNames.groupName(groupPrefix, guildId);
        run(api.getUserManager().modifyUser(player, user ->
                        user.data().remove(InheritanceNode.builder(name).build())),
                "убрать игрока из группы " + name);
    }

    // --------------------------------------------------------- внутреннее

    /**
     * Поставить группе суффикс, снеся прежний.
     *
     * Именно снеся: без очистки при каждой смене тега на группе копился бы
     * ещё один suffix, и какой из них выиграет — вопрос приоритетов, а не
     * замысла.
     */
    private CompletableFuture<Void> applySuffix(LuckPerms api, Group group, String tag) {
        group.data().clear(NodeType.SUFFIX::matches);
        group.data().add(SuffixNode.builder(
                suffixFormat.replace("{tag}", tag), SUFFIX_PRIORITY).build());
        return api.getGroupManager().saveGroup(group);
    }

    private LuckPerms api() {
        try {
            return LuckPermsProvider.get();
        } catch (IllegalStateException | NoClassDefFoundError e) {
            return null;
        }
    }

    /**
     * Дождаться операции, не подвесив вызывающего навсегда.
     *
     * Зовётся из рабочего потока гильдий, поэтому ожидание здесь допустимо —
     * но ограниченное: недоступное хранилище LuckPerms не должно заблокировать
     * очередь операций с гильдиями.
     */
    private void run(CompletableFuture<?> future, String what) {
        try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Не отменяет уже состоявшееся действие с гильдией: суффикс — это
            // украшение, а гильдия — данные.
            logger.log(Level.WARNING, "LuckPerms: не удалось " + what, e);
        }
    }
}
