package ovh.aurumgg.companion.paper;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Ник игрока из EssentialsX.
 *
 * <h2>Что здесь считается «алиасом»</h2>
 *
 * Никнейм, который игрок поставил себе сам через {@code /nick} (право на это
 * есть не у всех). Это НЕ история смены настоящего имени: если человек зайдёт
 * под другим реальным ником, сервер в офлайн-режиме увидит совсем другой UUID,
 * потому что UUID там вычисляется из самого имени. Связать такие два входа
 * никнеймами Essentials нельзя, и это не задача этого класса.
 *
 * <h2>Почему через отражение, а не зависимостью</h2>
 *
 * Ради одного строкового поля тянуть в сборку API EssentialsX вместе с его
 * репозиторием — несоразмерно. Тем же способом здесь уже работает
 * {@link InvSeeIntegration}, и по той же причине.
 *
 * Цепочка вызовов:
 * <pre>
 *   Plugin p = pluginManager.getPlugin("Essentials");
 *   Object user = p.getUser(UUID);          // com.earth2me.essentials.User
 *   String nick = user.getNickname();       // null, если ник не задан
 * </pre>
 *
 * <h2>Про цену</h2>
 *
 * {@code getUser} для игрока не в сети ЧИТАЕТ ЕГО ФАЙЛ в
 * {@code plugins/Essentials/userdata}. Для одного игрока это ничего не стоит,
 * для тысячи — тысяча обращений к диску. Поэтому зовётся это только для тех
 * записей, которые реально уезжают на экран (см. постраничность в
 * {@code BukkitGameBridge#knownPlayers}), а не для всего списка.
 */
final class EssentialsIntegration {

    /** Имя EssentialsX в Bukkit — именно Essentials, а не EssentialsX. */
    static final String PLUGIN_NAME = "Essentials";

    /** Метод ищется один раз: рефлексия дорога, а плагин на месте не меняется. */
    private static volatile Method getUserMethod;
    private static volatile Method getNicknameMethod;

    private EssentialsIntegration() {}

    static boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) != null;
    }

    /**
     * Ник игрока или {@code null}.
     *
     * {@code null} означает три неразличимых снаружи случая: плагина нет, ник
     * не задан, до данных не добрались. Для показа это одно и то же — рядом с
     * именем просто ничего не появится.
     */
    static String nicknameOf(UUID playerUuid) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null) return null;

        try {
            Method getUser = getUserMethod;
            if (getUser == null || !getUser.getDeclaringClass().isInstance(plugin)) {
                getUser = plugin.getClass().getMethod("getUser", UUID.class);
                getUserMethod = getUser;
            }
            Object user = getUser.invoke(plugin, playerUuid);
            if (user == null) return null;

            Method getNickname = getNicknameMethod;
            if (getNickname == null || !getNickname.getDeclaringClass().isInstance(user)) {
                getNickname = user.getClass().getMethod("getNickname");
                getNicknameMethod = getNickname;
            }
            Object nickname = getNickname.invoke(user);
            if (!(nickname instanceof String text) || text.isBlank()) return null;
            return text;
        } catch (Exception | NoClassDefFoundError e) {
            // Версия EssentialsX несовместима либо данных нет. Ник — украшение,
            // и падать из-за него нельзя: список игроков должен показаться.
            return null;
        }
    }
}
