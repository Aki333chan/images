package ovh.aurumgg.companion.paper;

import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import ovh.aurumgg.auth.api.AurumAuthApi;
import ovh.aurumgg.auth.api.IpRecord;
import ovh.aurumgg.auth.api.ResetToken;
import ovh.aurumgg.companion.core.model.IpRecordInfo;

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

    /**
     * Выдать игроку токен сброса пароля.
     *
     * Вызывается из HTTP-обработчика, то есть уже вне главного потока, и
     * возвращает future — сам плагин авторизации ходит за этим в MariaDB.
     *
     * Пусто, если AurumAuth не установлен: сбрасывать нечего, паролей у
     * сервера просто нет.
     */
    static CompletableFuture<Optional<ResetToken>> issueResetToken(String username) {
        return provider()
                .map(api -> api.issueResetToken(username))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()));
    }

    /** Сколько ждать ответа базы. Столько же, сколько у сброса пароля. */
    private static final long TIMEOUT_SECONDS = 5;

    /**
     * Адреса, с которых заходил игрок.
     *
     * Пустой список означает и «плагина авторизации нет», и «адресов не
     * записано» — для панели это одно и то же: показывать нечего.
     */
    static List<IpRecordInfo> ipHistory(UUID playerUuid) {
        return provider()
                .map(api -> {
                    try {
                        List<IpRecordInfo> result = new ArrayList<>();
                        for (IpRecord record :
                                api.ipHistory(playerUuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            result.add(new IpRecordInfo(
                                    record.ip(),
                                    record.firstSeen().toEpochMilli(),
                                    record.lastSeen().toEpochMilli()));
                        }
                        return result;
                    } catch (Exception e) {
                        // База не ответила. Адреса — необязательная часть
                        // карточки игрока, и рушить из-за них всю карточку
                        // нельзя: блок просто не появится.
                        return List.<IpRecordInfo>of();
                    }
                })
                .orElseGet(List::of);
    }

    /**
     * Ники всех зарегистрированных, в нижнем регистре.
     *
     * {@code null} здесь значит РОВНО «плагина авторизации нет», а пустое
     * множество — «плагин есть, но никто ещё не зарегистрирован». Разница
     * важна: в первом случае панель показывает исторический список целиком,
     * без деления, во втором честно говорит, что незарегистрированы все.
     */
    static Set<String> registeredUsernames() {
        AurumAuthApi api = provider().orElse(null);
        if (api == null) return null;
        try {
            return api.registeredUsernames().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Плагин есть, но не ответил. Считаем, что делить не на что, —
            // список покажется целиком, и это лучше, чем пометить всех
            // незарегистрированными.
            return null;
        }
    }
}
