package ovh.aurumgg.guilds.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ovh.aurumgg.guilds.core.GuildService;

/**
 * Мелочи вокруг входа и выхода игрока.
 *
 * Ник обновляется при каждом заходе: в базе он лежит копией для показа, а не
 * ключом, и без обновления панель показывала бы состав гильдии по никам
 * годичной давности. Сравнение и запись делает сам сервис — если ник не
 * менялся, в базу ничего не уходит.
 *
 * На выходе забывается доска сайдбара: иначе объект Scoreboard остался бы в
 * памяти на каждого, кто когда-либо заходил.
 */
final class PlayerTracker implements Listener {

    private final GuildService guilds;
    private final SidebarKeeper sidebar;

    PlayerTracker(GuildService guilds, SidebarKeeper sidebar) {
        this.guilds = guilds;
        this.sidebar = sidebar;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        guilds.touchUsername(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sidebar.forget(event.getPlayer().getUniqueId());
    }
}
