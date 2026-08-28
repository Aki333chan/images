package ovh.aurumgg.auth.paper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.auth.api.AurumAuthApi;
import ovh.aurumgg.auth.core.AuthConfig;
import ovh.aurumgg.auth.core.AuthService;
import ovh.aurumgg.auth.core.DeferredMessages;
import ovh.aurumgg.auth.core.LoginThrottle;
import ovh.aurumgg.auth.core.MariaDbAuthRepository;
import ovh.aurumgg.auth.core.MessageSettings;
import ovh.aurumgg.auth.core.PasswordHasher;
import ovh.aurumgg.auth.core.SessionStore;
import ovh.aurumgg.auth.core.premium.PremiumChecker;

/**
 * Точка входа плагина авторизации.
 *
 * Плагин намеренно маленький и занят ровно одним делом. Всё, что сложнее
 * склейки, живёт в модуле core и проверяется тестами; здесь остаются только
 * регистрация слушателей, команд и сервиса.
 */
public final class AurumAuthPlugin extends JavaPlugin {

    /** Как часто выбрасывать протухшие сессии и записи о попытках входа. */
    private static final long PURGE_PERIOD_TICKS = 20L * 60 * 5;

    private AuthService service;
    private AuthConfig config;
    private JoinMessageListener joinMessages;
    private final DeferredMessages<Component> deferredJoins = new DeferredMessages<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Map<String, Object> raw = new HashMap<>(getConfig().getValues(true));
        config = AuthConfig.fromMap(raw);

        String requestedTable = String.valueOf(raw.getOrDefault("database.table", AuthConfig.DEFAULT_TABLE));
        if (!requestedTable.equals(config.tableName())) {
            getLogger().warning("Имя таблицы «" + requestedTable + "» не годится для SQL — "
                    + "используется «" + config.tableName() + "»");
        }

        MariaDbAuthRepository repository;
        try {
            repository = new MariaDbAuthRepository(config);
            repository.initSchema();
        } catch (Exception e) {
            // БЕЗ БАЗЫ ПЛАГИН НЕ ЗАПУСКАЕТСЯ, И ЭТО ВАЖНО. Работающий сервер с
            // неработающей авторизацией — это сервер, на который можно зайти
            // под любым ником. Лучше не подняться совсем: администратор увидит
            // это сразу, а игроки не успеют натворить дел.
            getLogger().log(Level.SEVERE, "Не удалось подключиться к базе авторизации", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PremiumChecker premium = PremiumChecker.overNetwork(
                config.premiumEndpoint(),
                config.premiumTimeout(),
                config.premiumEnabled(),
                config.premiumCacheTtl());

        service = new AuthService(
                config,
                repository,
                new PasswordHasher(config.bcryptCost()),
                new SessionStore(config.sessionWindow()),
                new LoginThrottle(config.maxAttempts(), config.lockout(), config.attemptDelay()),
                getLogger(),
                Instant::now);

        AuthGuardListener guard = new AuthGuardListener(this, service, config);
        getServer().getPluginManager().registerEvents(guard, this);
        getServer().getPluginManager().registerEvents(
                new PreLoginListener(service, premium, config), this);
        joinMessages = new JoinMessageListener(service, config, deferredJoins);
        getServer().getPluginManager().registerEvents(joinMessages, this);

        // Без команд плагин бесполезен: войти будет нечем, а игроков уже
        // никуда не пускает. Поэтому не «пропускаем», а выключаемся.
        var login = getCommand("login");
        var register = getCommand("register");
        var reset = getCommand("reset");
        var admin = getCommand("auth");
        var twoFactor = getCommand("2fa");
        var unregister = getCommand("unregister");
        if (login == null || register == null || reset == null || admin == null
                || twoFactor == null || unregister == null) {
            getLogger().severe("Команды плагина не объявлены в plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        login.setExecutor(new LoginCommand(this, service, guard));
        register.setExecutor(new RegisterCommand(this, service, guard));
        reset.setExecutor(new ResetCommand(this, service, guard));
        twoFactor.setExecutor(new TwoFactorCommand(this, service, guard, config));
        unregister.setExecutor(new UnregisterCommand(this, service, guard));
        AuthAdminCommand adminCommand = new AuthAdminCommand(this, service);
        admin.setExecutor(adminCommand);
        admin.setTabCompleter(adminCommand);

        // Регистрация сервиса — то, ради чего companion больше не лазит в БД.
        // ServicePriority.Normal: провайдер у этого интерфейса один, но если
        // однажды появится второй, приоритет решит, кого возьмут.
        getServer().getServicesManager().register(
                AurumAuthApi.class, new BukkitAuthApi(service), this, ServicePriority.Normal);

        getServer().getScheduler().runTaskTimerAsynchronously(
                this, service::purge, PURGE_PERIOD_TICKS, PURGE_PERIOD_TICKS);

        getLogger().info("Авторизация включена. Premium-проверка: "
                + (config.premiumEnabled() ? "да" : "нет")
                + ", сессия: " + config.sessionWindow().toMinutes() + " мин"
                + ", сообщения о входе: " + config.joinMessageMode());

        if (config.premiumEnabled() && config.premiumSkipPassword()) {
            // Отдельной строкой в лог, потому что об этом легко забыть: без
            // прокси, делающего online-mode авторизацию, эта настройка не
            // сработает ни разу — и это правильное поведение, а не поломка.
            getLogger().info("Пароль пропускается только при подтверждённом UUID "
                    + "(вход через прокси в online-mode). Совпадения ника с лицензией для этого мало.");
        }
    }

    @Override
    public void onDisable() {
        if (service != null) {
            getServer().getServicesManager().unregisterAll(this);
            service.close();
        }
    }

    /** Сообщение игроку с общим для плагина префиксом. */
    static Component prefixed(String text) {
        return Component.text("[Вход] " + text);
    }

    /**
     * Показать сообщение о входе, придержанное до авторизации, и приветствие.
     *
     * Только с главного потока — зовётся из обработчиков команд после того,
     * как они туда вернулись, и из слушателя входа следующим тиком.
     */
    void releaseJoinMessage(java.util.UUID uuid) {
        if (joinMessages != null) joinMessages.releaseJoinMessage(uuid);
    }

    /**
     * Перечитать тексты сообщений с диска (/auth reload).
     *
     * Перечитываются ТОЛЬКО тексты, и это не полумера. Остальное — адрес базы,
     * размер пула, стоимость bcrypt — нельзя поменять на живом сервере, не
     * пересоздав пул соединений и не оставив на это время вход неработающим.
     * А правят в конфиге чаще всего как раз тексты.
     */
    void reloadMessages() {
        reloadConfig();
        MessageSettings updated = MessageSettings.fromMap(getConfig().getValues(true));
        if (joinMessages != null) joinMessages.updateMessages(updated);
        getLogger().info("Тексты сообщений перечитаны");
    }

    /** Тик-эквивалент длительности: планировщик Bukkit меряет время только тиками. */
    static long ticks(Duration duration) {
        return Math.max(1L, duration.toMillis() / 50L);
    }
}
