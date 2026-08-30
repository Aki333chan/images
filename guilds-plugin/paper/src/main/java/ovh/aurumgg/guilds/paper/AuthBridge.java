package ovh.aurumgg.guilds.paper;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.auth.event.PlayerAccountDeletedEvent;
import ovh.aurumgg.guilds.core.GuildService;

/**
 * Аккаунт игрока удалили — убираем его из гильдии.
 *
 * <h2>Зачем</h2>
 *
 * После удаления регистрации UUID остаётся в составе гильдии, за ним больше
 * никого нет, а на offline-сервере этот же ник вместе с UUID может занять уже
 * другой человек — и обнаружить себя в чужой гильдии офицером. Событие даёт
 * прибраться в тот же момент.
 *
 * Если удалённый был лидером, лидерство переходит следующему по старшинству, и
 * только если после этого не осталось никого — гильдия распускается. Правило
 * общее с административным исключением и живёт в {@code GuildService}.
 *
 * <h2>Зависимость мягкая</h2>
 *
 * Класс {@code PlayerAccountDeletedEvent} упоминается только здесь, и слушатель
 * регистрируется лишь после {@link #installed()}. Без AurumAuth класс не
 * загружается, эта автоматика просто не работает — и это рабочее положение
 * дел, а не деградация: убрать игрока вручную можно командой
 * {@code /guild admin remove}, а системы аккаунтов, из которой можно было бы
 * узнать об удалении, на таком сервере попросту нет.
 */
final class AuthBridge implements Listener {

    static final String PLUGIN_NAME = "AurumAuth";

    private final GuildService guilds;
    private final Plugin plugin;

    AuthBridge(Plugin plugin, GuildService guilds) {
        this.plugin = plugin;
        this.guilds = guilds;
    }

    /**
     * Установлена ли система авторизации.
     *
     * Проверяется по PluginManager, до касания класса события: иначе на сервере
     * без AurumAuth регистрация слушателя уронила бы старт плагина.
     */
    static boolean installed() {
        return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) != null;
    }

    /**
     * Приоритет MONITOR: мы ничего не отменяем и не меняем, только реагируем на
     * уже свершившийся факт. Само событие не отменяемое — аккаунт к этому
     * моменту из базы уже удалён.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAccountDeleted(PlayerAccountDeletedEvent event) {
        guilds.onAccountDeleted(event.uuid(), event.username()).thenAccept(result ->
                plugin.getLogger().info("Удаление аккаунта " + event.username() + ": "
                        + result.message()));
    }
}
