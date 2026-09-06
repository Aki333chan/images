package ovh.aurumgg.guilds.paper;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.World;

/**
 * Дом гильдии: весь её состав — участники региона WorldGuard.
 *
 * <h2>Зачем это нужно</h2>
 *
 * Лидер приватит территорию под дом гильдии и хочет, чтобы туда пускало всех
 * своих. Вручную это означает добавлять каждого нового участника в регион и
 * не забывать убирать ушедших — а не забывают ровно до первой ссоры, после
 * которой выгнанный из гильдии по-прежнему заходит в её сундуки.
 *
 * Поэтому связь ставится один раз, а состав держится в согласии сам:
 * вступил — добавили в регион, вышел — убрали.
 *
 * <h2>Что трогается, а что нет</h2>
 *
 * ТОЛЬКО СПИСОК УЧАСТНИКОВ (members). Владельцы (owners) не трогаются никогда:
 * владелец — это тот, кто регион создал, и отобрать у него права плагин
 * гильдий не вправе. Флаги региона, его границы и приоритет тоже не наши.
 *
 * <h2>Зависимость мягкая</h2>
 *
 * Классы {@code com.sk89q.*} упоминаются только здесь, и объект создаётся лишь
 * после {@link #installed()}. На сервере без WorldGuard класс не загружается
 * вовсе, а команда честно отвечает, что привязывать не к чему.
 */
final class WorldGuardBridge {

    static final String PLUGIN_NAME = "WorldGuard";

    private final Logger logger;

    WorldGuardBridge(Logger logger) {
        this.logger = logger;
    }

    static boolean installed() {
        return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) != null;
    }

    /** Что пошло не так — или {@code OK}. */
    enum Result {
        OK,
        /** Нет такого региона в этом мире. */
        NO_REGION,
        /** Игрок не владелец региона: чужую землю гильдии не отдать. */
        NOT_OWNER,
        /** WorldGuard есть, но регионы в этом мире выключены. */
        NO_MANAGER
    }

    /**
     * Проверить регион и добавить в него участников.
     *
     * Владение проверяется здесь, а не в core: кто хозяин региона, знает
     * только WorldGuard.
     */
    Result attach(World world, String regionId, UUID actor, Collection<UUID> members) {
        RegionManager manager = managerFor(world);
        if (manager == null) return Result.NO_MANAGER;

        ProtectedRegion region = manager.getRegion(regionId);
        if (region == null) return Result.NO_REGION;
        // Владелец, а не просто участник: участника в регион мог добавить кто
        // угодно, и разрешать ему раздавать чужую землю гильдии нельзя.
        if (!region.getOwners().contains(actor)) return Result.NOT_OWNER;

        for (UUID member : members) region.getMembers().addPlayer(member);
        return Result.OK;
    }

    /**
     * Убрать участников гильдии из региона.
     *
     * Владение здесь не проверяется намеренно: отвязка случается и когда
     * гильдию распустили, и когда человека из неё выгнали, — и в обоих случаях
     * спрашивать разрешения не у кого.
     */
    void removeMembers(World world, String regionId, Collection<UUID> members) {
        RegionManager manager = managerFor(world);
        if (manager == null) return;
        ProtectedRegion region = manager.getRegion(regionId);
        if (region == null) return;
        for (UUID member : members) region.getMembers().removePlayer(member);
    }

    /** Добавить одного человека — он только что вступил в гильдию. */
    void addMember(World world, String regionId, UUID member) {
        RegionManager manager = managerFor(world);
        if (manager == null) return;
        ProtectedRegion region = manager.getRegion(regionId);
        if (region == null) return;
        region.getMembers().addPlayer(member);
    }

    /**
     * Регионы этого мира, владелец которых — этот игрок.
     *
     * Для автодополнения {@code /guild claim}: подсказывать все регионы мира
     * бессмысленно и вредно — привязать всё равно можно только свой, а список
     * чужих приватов это карта того, где на сервере есть что взять.
     */
    List<String> ownedRegions(World world, UUID actor) {
        RegionManager manager = managerFor(world);
        if (manager == null) return List.of();
        List<String> owned = new ArrayList<>();
        for (ProtectedRegion region : manager.getRegions().values()) {
            if (region.getOwners().contains(actor)) owned.add(region.getId());
        }
        return owned;
    }

    /** Существует ли такой регион — для подсказок и понятных отказов. */
    boolean regionExists(World world, String regionId) {
        RegionManager manager = managerFor(world);
        return manager != null && manager.getRegion(regionId) != null;
    }

    /** Неизменяемая и безопасная для показа часть данных региона. */
    record RegionSnapshot(String id, int priority, List<String> owners) {}

    /**
     * Все регионы в точном блоке, сначала самый приоритетный.
     *
     * <p>Запрос идёт по пространственному индексу WorldGuard, а не перебирает
     * все приваты мира. Поэтому его допустимо делать на клик без фоновой
     * задачи и без заметного влияния на MSPT.</p>
     */
    List<RegionSnapshot> at(Location location, boolean ignoreGlobal) {
        RegionManager manager = managerFor(location.getWorld());
        if (manager == null) return List.of();
        try {
            BlockVector3 point = BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            return manager.getApplicableRegions(point).getRegions().stream()
                    .filter(region -> !ignoreGlobal || !"__global__".equalsIgnoreCase(region.getId()))
                    .sorted(Comparator.comparingInt(ProtectedRegion::getPriority).reversed()
                            .thenComparing(ProtectedRegion::getId))
                    .map(region -> new RegionSnapshot(
                            region.getId(), region.getPriority(), ownerNames(region)))
                    .toList();
        } catch (RuntimeException error) {
            logger.warning("WorldGuard не смог проверить блок " + location + ": " + error);
            return List.of();
        }
    }

    private static List<String> ownerNames(ProtectedRegion region) {
        Set<String> result = new LinkedHashSet<>(region.getOwners().getPlayers());
        for (UUID uuid : region.getOwners().getUniqueIds()) {
            Player online = Bukkit.getPlayer(uuid);
            String name = online == null ? null : online.getName();
            if (name == null) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                name = offline.getName();
            }
            result.add(name == null ? uuid.toString() : name);
        }
        for (String group : region.getOwners().getGroups()) result.add("g:" + group);
        return List.copyOf(result);
    }

    /**
     * Менеджер регионов мира.
     *
     * Адрес мира для WorldGuard — это тип WorldEdit, отсюда BukkitAdapter.
     * null означает, что в этом мире регионы выключены; это не ошибка, а
     * настройка WorldGuard, и падать из-за неё незачем.
     */
    private RegionManager managerFor(World world) {
        try {
            return WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(world));
        } catch (Exception | NoClassDefFoundError e) {
            logger.warning("WorldGuard не ответил: " + e);
            return null;
        }
    }
}
