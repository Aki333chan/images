package ovh.aurumgg.companion.paper;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import ovh.aurumgg.auth.api.AurumAuthApi;

/**
 * Система авторизации — через её собственный API, а не через её базу.
 *
 * ЧТО ЗДЕСЬ ИЗМЕНИЛОСЬ ПО СРАВНЕНИЮ С AuthMe. Раньше вопрос «вошёл ли игрок»
 * companion решал сам: лез SQL-запросом в таблицу AuthMe и читал оттуда
 * isLogged и hasSession. Это работало ровно до тех пор, пока AuthMe не менял
 * схему, и требовало держать в конфиге companion ещё одну пару логин-пароль от
 * чужой базы.
 *
 * Теперь спрашиваем у AurumAuth через ServicesManager — тем же приёмом, каким
 * companion уже работает с Vault и LuckPerms. Никаких SQL, никаких чужих
 * паролей в конфиге, и схема таблицы становится внутренним делом плагина
 * авторизации.
 *
 * Провайдера НЕ кэшируем в поле: плагин авторизации могут перезагрузить на
 * живом сервере, и ссылка на старый экземпляр означала бы тихо неверные
 * ответы про то, кто вошёл.
 */
final class AuthIntegration {

    private AuthIntegration() {}

    /** API или пусто, если AurumAuth не установлен. */
    static Optional<AurumAuthApi> provider() {
        // Проверка плагина перед обращением к классу нужна на случай, когда
        // AurumAuth не установлен вовсе: getRegistration уронил бы поток
        // NoClassDefFoundError, а не вернул пусто.
        if (Bukkit.getPluginManager().getPlugin("AurumAuth") == null) return Optional.empty();
        try {
            RegisteredServiceProvider<AurumAuthApi> registration =
                    Bukkit.getServer().getServicesManager().getRegistration(AurumAuthApi.class);
            return registration == null ? Optional.empty() : Optional.ofNullable(registration.getProvider());
        } catch (NoClassDefFoundError | Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Вошёл ли игрок прямо сейчас.
     *
     * ОТВЕТ ПРИ ОТСУТСТВУЮЩЕЙ АВТОРИЗАЦИИ — «ДА», И ЭТО ОСОЗНАННО. Если
     * AurumAuth на сервере не стоит, то и понятия «не вошёл» не существует:
     * все, кто подключился, считаются игроками. Ответ «нет» в этом случае
     * означал бы, что /webtoken не работает ни у кого на сервере без
     * авторизации — то есть companion сломался бы там, где ломаться нечему.
     */
    static boolean isAuthenticated(UUID playerUuid) {
        return provider().map(api -> api.isAuthenticated(playerUuid)).orElse(true);
    }

    /** Установлена ли система авторизации — для понятных сообщений и логов. */
    static boolean installed() {
        return provider().isPresent();
    }
}
