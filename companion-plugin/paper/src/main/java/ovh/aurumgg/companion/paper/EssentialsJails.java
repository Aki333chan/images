package ovh.aurumgg.companion.paper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.companion.core.model.JailedPlayer;
import ovh.aurumgg.companion.core.model.JailsInfo;
import ovh.aurumgg.companion.core.model.PlayerJailState;

/**
 * Тюрьмы EssentialsX — чтение.
 *
 * <h2>Почему отражением</h2>
 *
 * По той же причине, что и ник в {@link EssentialsIntegration}: тянуть в
 * сборку API EssentialsX ради двух списков несоразмерно, а плагин
 * необязателен — на сервере без него companion обязан работать как ни в чём
 * не бывало.
 *
 * <pre>
 *   Plugin p = pluginManager.getPlugin("Essentials");
 *   Object jails = p.getJails();            // net.ess3.api.IJails
 *   Collection&lt;String&gt; names = jails.getList();
 *
 *   Object user = p.getUser(Player);        // com.earth2me.essentials.User
 *   boolean sitting = user.isJailed();
 *   String where   = user.getJail();
 *   long   until   = user.getJailTimeout(); // 0 — бессрочно
 * </pre>
 *
 * <h2>Чего EssentialsX НЕ хранит</h2>
 *
 * <b>Момента посадки.</b> В файле игрока лежит только время, когда его
 * ВЫПУСТЯТ ({@code getJailTimeout}), и ноль, если срок не назначен. Поэтому
 * «сколько уже сидит» отсюда получить нельзя ни при каких условиях — это
 * панель помнит сама, по своим же посадкам. Отсюда же уезжает только
 * остаток срока: его плагин знает точно.
 *
 * <h2>Почему только те, кто в сети</h2>
 *
 * Признак «сидит» лежит в userdata игрока, отдельного списка сидящих у
 * EssentialsX нет. Найти всех — значит прочитать файл каждого, кто когда-либо
 * заходил; на живом сервере это тысячи обращений к диску на каждый показ
 * страницы. Онлайн стоит ноль: объекты игроков и так в памяти.
 *
 * <h2>Про одного офлайн-игрока спросить всё-таки можно</h2>
 *
 * {@link #state(String)} читает файл ОДНОГО игрока по нику — это одно
 * обращение к диску, и оно того стоит: без состояния нельзя собрать
 * правильную команду {@code togglejail}, а сажать офлайн-игроков панель
 * должна. Цепочка та же, только пользователь берётся по имени:
 *
 * <pre>
 *   Object user = p.getUser(String);  // = getOfflineUser, null если не знает
 * </pre>
 */
final class EssentialsJails {

    /** Методы ищутся один раз: отражение дорого, а плагин на месте не меняется. */
    private static volatile Method getJailsMethod;
    private static volatile Method getListMethod;
    private static volatile Method getUserMethod;
    private static volatile Method getUserByNameMethod;
    private static volatile Method isJailedMethod;
    private static volatile Method getJailMethod;
    private static volatile Method getJailTimeoutMethod;

    private EssentialsJails() {}

    static JailsInfo read() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(EssentialsIntegration.PLUGIN_NAME);
        if (plugin == null) return JailsInfo.unavailable();

        List<String> names;
        try {
            names = jailNames(plugin);
        } catch (Exception | NoClassDefFoundError e) {
            // Версия EssentialsX несовместима. Не «тюрем нет», а «спросить не
            // удалось»: панель покажет поле для имени руками, а не пустой
            // список, из которого нечего выбрать.
            return JailsInfo.unavailable();
        }

        List<JailedPlayer> jailed = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                JailedPlayer record = jailedOrNull(plugin, player);
                if (record != null) jailed.add(record);
            } catch (Exception | NoClassDefFoundError e) {
                // Один игрок с нечитаемым файлом не должен уносить весь
                // список: остальных показать полезнее, чем никого.
            }
        }
        return new JailsInfo(true, names, List.copyOf(jailed));
    }

    /**
     * Состояние одного игрока по нику. Работает и для тех, кого нет в сети.
     *
     * {@code getUser(String)} у EssentialsX — это {@code getOfflineUser}: он
     * ищет по карте ников и при попадании читает файл игрока. {@code null}
     * означает «такого не знаю» — например, ник с опечаткой или человек,
     * который ни разу не заходил.
     */
    static PlayerJailState state(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(EssentialsIntegration.PLUGIN_NAME);
        if (plugin == null) return PlayerJailState.unknown();

        try {
            Method getUser = getUserByNameMethod;
            if (getUser == null || !getUser.getDeclaringClass().isInstance(plugin)) {
                getUser = plugin.getClass().getMethod("getUser", String.class);
                getUserByNameMethod = getUser;
            }
            Object user = getUser.invoke(plugin, name);
            if (user == null) return PlayerJailState.unknown();

            boolean online = Bukkit.getPlayerExact(name) != null;
            if (!jailed(user)) return new PlayerJailState(true, false, "", 0L, online);
            return new PlayerJailState(true, true, jailName(user), jailTimeout(user), online);
        } catch (Exception | NoClassDefFoundError e) {
            // Версия EssentialsX несовместима либо файл игрока не читается.
            // «Не знаю» честнее выдуманного «не сидит»: на последнем панель
            // отправила бы команду посадки тому, кто уже сидит.
            return PlayerJailState.unknown();
        }
    }

    private static boolean jailed(Object user) throws Exception {
        Method isJailed = isJailedMethod;
        if (isJailed == null || !isJailed.getDeclaringClass().isInstance(user)) {
            isJailed = user.getClass().getMethod("isJailed");
            isJailedMethod = isJailed;
        }
        return Boolean.TRUE.equals(isJailed.invoke(user));
    }

    private static String jailName(Object user) throws Exception {
        Method getJail = getJailMethod;
        if (getJail == null || !getJail.getDeclaringClass().isInstance(user)) {
            getJail = user.getClass().getMethod("getJail");
            getJailMethod = getJail;
        }
        Object where = getJail.invoke(user);
        return where == null ? "" : String.valueOf(where);
    }

    private static long jailTimeout(Object user) throws Exception {
        Method getTimeout = getJailTimeoutMethod;
        if (getTimeout == null || !getTimeout.getDeclaringClass().isInstance(user)) {
            getTimeout = user.getClass().getMethod("getJailTimeout");
            getJailTimeoutMethod = getTimeout;
        }
        Object until = getTimeout.invoke(user);
        return until instanceof Long value ? value : 0L;
    }

    private static List<String> jailNames(Plugin plugin) throws Exception {
        Method getJails = getJailsMethod;
        if (getJails == null || !getJails.getDeclaringClass().isInstance(plugin)) {
            getJails = plugin.getClass().getMethod("getJails");
            getJailsMethod = getJails;
        }
        Object jails = getJails.invoke(plugin);
        if (jails == null) return List.of();

        Method getList = getListMethod;
        if (getList == null || !getList.getDeclaringClass().isInstance(jails)) {
            getList = jails.getClass().getMethod("getList");
            getListMethod = getList;
        }
        Object list = getList.invoke(jails);
        if (!(list instanceof Collection<?> raw)) return List.of();

        List<String> names = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item != null) names.add(String.valueOf(item));
        }
        return List.copyOf(names);
    }

    private static JailedPlayer jailedOrNull(Plugin plugin, Player player) throws Exception {
        Method getUser = getUserMethod;
        if (getUser == null || !getUser.getDeclaringClass().isInstance(plugin)) {
            getUser = plugin.getClass().getMethod("getUser", Player.class);
            getUserMethod = getUser;
        }
        Object user = getUser.invoke(plugin, player);
        if (user == null || !jailed(user)) return null;

        return new JailedPlayer(
                player.getUniqueId(), player.getName(), jailName(user), jailTimeout(user));
    }
}
