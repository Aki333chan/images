package ovh.aurumgg.guilds.paper;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import ovh.aurumgg.guilds.core.NameResolver;

/**
 * Ник по UUID — из сети, а если там нет, то из кэша сервера.
 *
 * Кэш офлайн-игроков (usercache.json) знает всех, кто заходил хотя бы раз, и
 * этого достаточно: в гильдии не бывает того, кто ни разу не был на сервере.
 * В сеть за ником не ходим намеренно — это сетевой запрос ради строки в
 * сообщении, и на медленном ответе он подвесил бы рабочий поток.
 */
final class PlayerNames implements NameResolver {

    @Override
    public String nameOf(UUID uuid) {
        if (uuid == null) return "неизвестный";
        var online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        // Ник неизвестен только у того, кого сервер никогда не видел. Показать
        // UUID честнее, чем «неизвестный»: по нему хотя бы можно что-то найти.
        return name != null ? name : uuid.toString();
    }

    /**
     * UUID по нику.
     *
     * Bukkit.getOfflinePlayer(String) на offline-сервере ВСЕГДА возвращает
     * объект — он просто вычисляет UUID из ника, — поэтому «нашли» и «такого
     * игрока не существует» по нему не различить. Сначала смотрим в сеть,
     * потом в кэш и только затем соглашаемся на вычисленный UUID.
     *
     * <h2>Почему не перебор getOfflinePlayers()</h2>
     *
     * Раньше кэш просматривался перебором ВСЕХ, кто когда-либо заходил.
     * Метод зовут обработчики команд — {@code /guild kick}, {@code /party
     * promote} и прочие, — то есть главный поток сервера; на сервере с
     * многолетней историей это тысячи объектов на каждое нажатие Enter.
     *
     * {@code getOfflinePlayerIfCached} отвечает на тот же вопрос поиском по
     * имени в usercache и, в отличие от {@code getOfflinePlayer(String)}, не
     * ходит за ником в сеть: он либо есть в кэше, либо возвращается null.
     */
    static UUID uuidOf(String name) {
        var online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) return cached.getUniqueId();
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }
}
