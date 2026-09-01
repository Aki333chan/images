package ovh.aurumgg.guilds.paper;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.guilds.core.GuildHooks;
import ovh.aurumgg.guilds.core.GuildRegion;
import ovh.aurumgg.guilds.core.GuildService;

/**
 * Состав дома гильдии держится в согласии с составом самой гильдии.
 *
 * <h2>Ради чего</h2>
 *
 * Без этого привязка региона протухала бы на второй же неделе: новых
 * участников пришлось бы добавлять руками, а выгнанный из гильдии продолжал бы
 * заходить в её сундуки — и заметили бы это ровно тогда, когда он ими
 * воспользуется.
 *
 * <h2>Почему через основной поток</h2>
 *
 * Гильдия меняется в рабочем потоке сервиса, а правка региона — обращение к
 * WorldGuard. Его состояние живёт вместе с миром, и трогать его со стороннего
 * потока не следует, даже если сегодня это сходит с рук.
 *
 * <h2>Роспуск гильдии</h2>
 *
 * {@code guildDeleted} здесь ничего не делает НАМЕРЕННО. Связи гильдии с
 * регионами уходят вместе с ней (внешний ключ с каскадом), но участников из
 * региона убирать некому и незачем: регион остаётся у своего владельца, а
 * список участников он поправит сам, если захочет. Стирать чужой список при
 * роспуске значило бы, что распустивший гильдию лидер заодно выгнал всех из
 * собственного дома.
 */
final class RegionSyncHooks implements GuildHooks {

    private final Plugin plugin;
    /**
     * Поставщик, а не сам сервис: хуки собираются раньше, чем сервис создан, —
     * он их и получает в конструкторе.
     */
    private final Supplier<GuildService> guilds;
    private final WorldGuardBridge regions;

    RegionSyncHooks(Plugin plugin, Supplier<GuildService> guilds, WorldGuardBridge regions) {
        this.plugin = plugin;
        this.guilds = guilds;
        this.regions = regions;
    }

    @Override
    public void guildCreated(long guildId, String tag) {}

    @Override
    public void guildDeleted(long guildId) {}

    @Override
    public void tagChanged(long guildId, String tag) {}

    @Override
    public void memberJoined(long guildId, UUID player) {
        forEachRegion(guildId, (world, regionId) -> regions.addMember(world, regionId, player));
    }

    @Override
    public void memberLeft(long guildId, UUID player) {
        forEachRegion(guildId, (world, regionId) ->
                regions.removeMembers(world, regionId, List.of(player)));
    }

    private interface RegionAction {
        void run(World world, String regionId);
    }

    private void forEachRegion(long guildId, RegionAction action) {
        GuildService service = guilds.get();
        if (service == null) return;
        List<GuildRegion> attached = service.regions(guildId);
        if (attached.isEmpty()) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (GuildRegion region : attached) {
                World world = Bukkit.getWorld(region.world());
                // Мира может не быть: его выгрузили или переименовали. Это не
                // ошибка нашего плагина, и падать из-за неё незачем.
                if (world != null) action.run(world, region.regionId());
            }
        });
    }
}
