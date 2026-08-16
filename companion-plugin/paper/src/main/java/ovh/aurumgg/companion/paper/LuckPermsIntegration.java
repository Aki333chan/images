package ovh.aurumgg.companion.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import ovh.aurumgg.companion.core.model.PermissionChange;
import ovh.aurumgg.companion.core.model.PermissionsInfo;

/**
 * Работа с правами через Developer API LuckPerms (net.luckperms.api).
 *
 * ПОЧЕМУ API, А НЕ RCON-КОМАНДА `lp user … parent add …`.
 * Через API видно, существует ли группа, и можно отличить «уже было» от
 * «применено» — RCON вернул бы строку текста, которую пришлось бы разбирать
 * регулярками, и та менялась бы от версии к версии. Плюс сразу после записи
 * читается актуальное состояние, без второго похода на сервер.
 *
 * ЗАВИСИМОСТЬ МЯГКАЯ. В plugin.yml LuckPerms стоит в softdepend, а обращения
 * к его классам собраны в этом файле. Если плагина нет, класс просто ни разу
 * не загружается: {@link #isAvailable()} проверяет наличие через
 * PluginManager, до касания net.luckperms.*. Поэтому NoClassDefFoundError на
 * сервере без LuckPerms не возникает.
 */
final class LuckPermsIntegration {

    /** Имя LuckPerms в Bukkit. */
    static final String PLUGIN_NAME = "LuckPerms";

    /** Операции с правами идут в хранилище (файл/БД) — ждём, но не вечно. */
    private static final long TIMEOUT_SECONDS = 5;

    private LuckPermsIntegration() {}

    /**
     * Чтение прав.
     *
     * @return пусто, если LuckPerms недоступен либо данных по игроку нет
     */
    static Optional<PermissionsInfo> read(UUID playerUuid) {
        LuckPerms api = api();
        if (api == null) return Optional.empty();

        User user = loadUser(api, playerUuid);
        if (user == null) return Optional.empty();

        List<String> groups = new ArrayList<>();
        for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
            // Отрицательные ноды — это «явно НЕ состоит в группе»,
            // показывать их в списке групп было бы неверно.
            if (node.getValue()) groups.add(node.getGroupName());
        }

        List<PermissionsInfo.PermissionEntry> permissions = new ArrayList<>();
        for (PermissionNode node : user.getNodes(NodeType.PERMISSION)) {
            permissions.add(new PermissionsInfo.PermissionEntry(node.getPermission(), node.getValue()));
        }

        groups.sort(String::compareToIgnoreCase);
        permissions.sort((a, b) -> a.permission().compareToIgnoreCase(b.permission()));

        return Optional.of(new PermissionsInfo(user.getPrimaryGroup(), groups, permissions));
    }

    /**
     * Применение одного изменения.
     *
     * @return пусто, если LuckPerms недоступен; иначе результат с причиной отказа
     */
    static Optional<PermissionChange.Result> apply(UUID playerUuid, PermissionChange change) {
        LuckPerms api = api();
        if (api == null) return Optional.empty();

        User user = loadUser(api, playerUuid);
        if (user == null) {
            return Optional.of(PermissionChange.Result.rejected("Игрок не найден в базе LuckPerms"));
        }

        Node node;
        if (change.kind() == PermissionChange.Kind.GROUP) {
            // Проверяем до записи: иначе игрок получит наследование от группы,
            // которой нет, и это тихо ничего не изменит.
            Group group = api.getGroupManager().getGroup(change.key());
            if (group == null) {
                return Optional.of(PermissionChange.Result.rejected(
                        "Группа «" + change.key() + "» не существует в LuckPerms"));
            }
            node = InheritanceNode.builder(group).value(change.value()).build();
        } else {
            node = PermissionNode.builder(change.key()).value(change.value()).build();
        }

        DataMutateResult result =
                change.remove() ? user.data().remove(node) : user.data().add(node);

        if (!result.wasSuccessful()) {
            return Optional.of(PermissionChange.Result.rejected(
                    change.remove()
                            ? "У игрока и так не было этой ноды"
                            : "У игрока уже есть такая нода"));
        }

        try {
            api.getUserManager().saveUser(user).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return Optional.of(PermissionChange.Result.rejected(
                    "LuckPerms не сохранил изменение: " + e.getClass().getSimpleName()));
        }
        return Optional.of(PermissionChange.Result.ok());
    }

    /** true, если LuckPerms установлен и его API поднят. */
    static boolean isAvailable() {
        return api() != null;
    }

    /**
     * Инстанс API или null.
     *
     * LuckPermsProvider.get() бросает IllegalStateException, если API ещё не
     * загружен, — для нас это то же самое, что «плагина нет».
     */
    private static LuckPerms api() {
        try {
            return LuckPermsProvider.get();
        } catch (IllegalStateException | NoClassDefFoundError e) {
            return null;
        }
    }

    /**
     * Загружает пользователя, в том числе офлайн: loadUser поднимает его из
     * хранилища, поэтому права можно смотреть и у того, кого нет на сервере.
     */
    private static User loadUser(LuckPerms api, UUID playerUuid) {
        UserManager users = api.getUserManager();
        try {
            CompletableFuture<User> future = users.loadUser(playerUuid);
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}
