package ovh.aurumgg.guilds.paper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ovh.aurumgg.guilds.api.AurumGuildsApi;
import ovh.aurumgg.guilds.core.EconomyBridge;
import ovh.aurumgg.guilds.core.GuildHooks;
import ovh.aurumgg.guilds.core.GuildService;
import ovh.aurumgg.guilds.core.GuildsConfig;
import ovh.aurumgg.guilds.core.MariaDbGuildRepository;
import ovh.aurumgg.guilds.core.PartyService;

/**
 * Точка входа AurumGuilds.
 *
 * <h2>Три необязательных плагина и как они подключаются</h2>
 *
 * LuckPerms, Vault и AurumAuth — все три soft-depend, и решение о каждом
 * принимается ровно здесь, один раз при старте. Проверка идёт через
 * PluginManager, ДО первого касания их классов: только после неё создаётся
 * соответствующий мост. Если плагина нет, вместо моста подставляется заглушка,
 * его классы не загружаются вовсе, а в лог уходит честная строчка о том, какая
 * часть возможностей выключена.
 *
 * Ни одного {@code depend} в plugin.yml нет — только {@code softdepend}, и
 * даже он влияет лишь на порядок загрузки, а не на возможность запуститься.
 */
public final class AurumGuildsPlugin extends JavaPlugin {

    /** Как часто выбрасывать истёкшие приглашения и брошенные пати. */
    private static final long HOUSEKEEPING_TICKS = 20L * 60;

    /**
     * Через сколько без единого игрока в сети пати считается брошенной.
     *
     * Вышедший из пати не удаляется — разрыв связи не должен разваливать
     * группу, — но и держать её вечно нельзя: это память, которую иначе
     * освободит только перезапуск.
     */
    private static final Duration PARTY_IDLE = Duration.ofMinutes(15);

    private GuildService guilds;
    private PartyService parties;
    private SidebarKeeper sidebar;
    /** Мост к LuckPerms или null — нужен перезагрузке, чтобы обновить формат суффикса. */
    private LuckPermsBridge luckPermsBridge;
    /** Задача HUD: перезагрузка её пересоздаёт, если поменялся период. */
    private BukkitTask hudTask;
    /** Настройки на момент последней загрузки — с ними сверяется перезагрузка. */
    private GuildsConfig config;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Map<String, Object> raw = new HashMap<>(getConfig().getValues(true));
        GuildsConfig config = GuildsConfig.fromMap(raw);
        this.config = config;

        String requestedPrefix = String.valueOf(
                raw.getOrDefault("database.table-prefix", GuildsConfig.DEFAULT_PREFIX));
        if (!requestedPrefix.equals(config.tablePrefix())) {
            getLogger().warning("Префикс таблиц «" + requestedPrefix + "» не годится для SQL — "
                    + "используется «" + config.tablePrefix() + "»");
        }

        // --- необязательные соседи ---
        //
        // LuckPerms решается один раз: он регистрирует свой API в собственном
        // onEnable, а softdepend гарантирует, что тот пройдёт раньше нашего.
        boolean luckPerms = LuckPermsBridge.installed();
        if (luckPerms) {
            luckPermsBridge = new LuckPermsBridge(
                    config.luckPermsGroupPrefix(), config.suffixFormat(), getLogger());
        }
        GuildHooks hooks = luckPerms ? luckPermsBridge : GuildHooks.noop();

        // А ВОТ С VAULT ТАК НЕЛЬЗЯ, И ЗДЕСЬ БЫЛА ОШИБКА.
        //
        // Vault сам денег не хранит — это шина. Провайдера экономики
        // регистрирует ТРЕТИЙ плагин (EssentialsX, CMI, любой другой), и его в
        // нашем softdepend нет и быть не может: мы не знаем, какой именно
        // стоит на сервере. Значит, его onEnable вполне может пройти позже
        // нашего, и на момент старта провайдера ещё нет.
        //
        // Прежний код спрашивал об этом ровно один раз и, не увидев
        // провайдера, навсегда подставлял заглушку: банк оставался выключенным
        // до перезапуска, хотя Vault на сервере есть и работает. Ровно на это
        // и жаловались.
        //
        // VaultBridge и так спрашивает провайдера при каждом обращении —
        // достаточно перестать решать за него заранее. Нет Vault вообще →
        // available() честно вернёт false, и банк просто не работает; появился
        // провайдер через минуту после старта → банк заработает сам.
        EconomyBridge economy = config.bankEnabled() ? new VaultBridge() : EconomyBridge.unavailable();

        MariaDbGuildRepository repository;
        try {
            repository = new MariaDbGuildRepository(config);
            repository.initSchema();
        } catch (Exception e) {
            // БЕЗ БАЗЫ ПЛАГИН НЕ ЗАПУСКАЕТСЯ. Работающие команды при
            // неработающем хранилище означали бы гильдии, которые исчезают при
            // перезапуске, — и обнаружилось бы это только тогда, когда людям
            // уже есть что терять.
            getLogger().log(Level.SEVERE, "Не удалось подключиться к базе гильдий", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PlayerNames names = new PlayerNames();
        guilds = new GuildService(config, repository, hooks, economy, names, getLogger(), Instant::now);
        try {
            guilds.load();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Не удалось прочитать гильдии из базы", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        parties = new PartyService(
                Instant::now, names, config.maxPartyMembers(), config.partyInviteTtl());

        // ------------------------------- команды, слушатели, задачи --------
        ChatPrompt prompts = new ChatPrompt(this);
        GuildSettingsMenu menu = new GuildSettingsMenu(this, guilds, prompts);
        sidebar = new SidebarKeeper(config.hudTitle());

        getServer().getPluginManager().registerEvents(prompts, this);
        getServer().getPluginManager().registerEvents(menu, this);
        getServer().getPluginManager().registerEvents(
                new FriendlyFireListener(guilds, parties, () -> this.config.partyFriendlyFire()), this);
        getServer().getPluginManager().registerEvents(new PlayerTracker(guilds, sidebar), this);
        getServer().getPluginManager().registerEvents(new BonusDropListener(guilds), this);
        // Эффекты-бонусы продлеваются задачей: выданный однажды эффект зелья
        // кончился бы сам, а бесконечный остался бы после снятия бонуса.
        getServer().getScheduler().runTaskTimer(this, new BonusEffectsTask(guilds),
                BonusEffectsTask.PERIOD_TICKS, BonusEffectsTask.PERIOD_TICKS);

        if (!bindCommands(menu)) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        boolean auth = AuthBridge.installed();
        if (auth) {
            getServer().getPluginManager().registerEvents(new AuthBridge(this, guilds), this);
        }

        // Публичный API — то, ради чего companion не заводит второй HTTP-сервер.
        getServer().getServicesManager().register(
                AurumGuildsApi.class,
                new BukkitGuildsApi(guilds, parties, luckPerms),
                this,
                ServicePriority.Normal);

        restartHudTask();
        getServer().getScheduler().runTaskTimer(this, this::housekeeping,
                HOUSEKEEPING_TICKS, HOUSEKEEPING_TICKS);

        report(luckPerms, auth);
    }

    @Override
    public void onDisable() {
        if (sidebar != null) {
            // Иначе у всех, кто сейчас в сети, останется висеть сайдбар,
            // который больше некому обновлять.
            for (Player player : getServer().getOnlinePlayers()) sidebar.hide(player);
        }
        if (guilds != null) {
            getServer().getServicesManager().unregisterAll(this);
            guilds.close();
        }
    }

    private boolean bindCommands(GuildSettingsMenu menu) {
        var guild = getCommand("guild");
        var party = getCommand("party");
        var partyChat = getCommand("p");
        var guildChat = getCommand("g");
        if (guild == null || party == null || partyChat == null || guildChat == null) {
            getLogger().severe("Команды плагина не объявлены в plugin.yml");
            return false;
        }

        GuildCommand guildCommand = new GuildCommand(this, guilds, menu);
        guild.setExecutor(guildCommand);
        guild.setTabCompleter(guildCommand);

        PartyCommand partyCommand = new PartyCommand(parties);
        party.setExecutor(partyCommand);
        party.setTabCompleter(partyCommand);

        partyChat.setExecutor(
                new ChannelCommand(ChannelCommand.Channel.PARTY, guilds, parties));
        guildChat.setExecutor(
                new ChannelCommand(ChannelCommand.Channel.GUILD, guilds, parties));
        return true;
    }

    /**
     * Состояние банка человеческими словами.
     *
     * Три разных случая, и путать их нельзя: выключено хозяином сервера,
     * включено но провайдера пока нет, включено и работает. Второй — не
     * поломка: провайдер может появиться позже нашего старта, и банк
     * подхватит его сам.
     */
    private List<String> bankStatus() {
        if (!config.bankEnabled()) {
            return List.of("Банк гильдии выключен в config.yml (bank.enabled: false).");
        }
        if (Bukkit.getPluginManager().getPlugin(VaultBridge.PLUGIN_NAME) == null) {
            return List.of("Vault не установлен — банк гильдии недоступен. "
                    + "Всё остальное в гильдиях работает.");
        }
        if (!guilds.bankAvailable()) {
            return List.of(
                    "Vault есть, но провайдера экономики за ним пока нет — банк не работает.",
                    "Это может быть нормально: провайдера регистрирует плагин экономики "
                            + "(EssentialsX, CMI и т. п.), и он мог ещё не запуститься. "
                            + "Банк включится сам, как только провайдер появится — "
                            + "перезапуск не нужен.");
        }
        return List.of("Vault и провайдер экономики на месте: банк гильдии работает.");
    }

    /**
     * Перезапустить задачу HUD под текущий конфиг.
     *
     * Отдельным методом, потому что период задаётся при постановке задачи и
     * иначе живёт до перезапуска сервера: правка hud.refresh в config.yml
     * молча не действовала бы.
     */
    private void restartHudTask() {
        if (hudTask != null) {
            hudTask.cancel();
            hudTask = null;
        }
        if (!config.hudEnabled()) {
            // Выключили на ходу — снимаем сайдбар у всех, иначе он застынет на
            // экране навсегда: обновлять его больше некому.
            for (Player player : getServer().getOnlinePlayers()) sidebar.hide(player);
            return;
        }
        long period = Math.max(1, config.hudRefresh().toMillis() / 50);
        hudTask = getServer().getScheduler().runTaskTimer(
                this, new HudTask(guilds, parties, sidebar), period, period);
    }

    /** Разрешён ли сейчас урон по своим внутри пати. */
    boolean partyFriendlyFire() {
        return config.partyFriendlyFire();
    }

    /**
     * Переключить урон по своим в пати — и записать это в config.yml.
     *
     * Записать обязательно: настройка, которая слетает при перезапуске, хуже
     * отсутствующей. Человек выключит её командой, забудет, а через неделю
     * после рестарта получит жалобы на то, чего сам не менял.
     */
    void partyFriendlyFire(boolean allowed) {
        getConfig().set("party.friendly-fire", allowed);
        saveConfig();
        this.config = GuildsConfig.fromMap(new HashMap<>(getConfig().getValues(true)));
    }

    /**
     * Перечитать config.yml без перезапуска сервера.
     *
     * Применяется НЕ всё, и это честно сказано вызывающему. Настройки базы и
     * размер пула потоков остаются прежними: пересоздавать подключение под
     * идущими операциями на живом сервере — способ потерять транзакцию с
     * чужими деньгами ради удобства, которое нужно раз в месяц.
     *
     * Сервисы при этом НЕ пересоздаются: в них лежат пати, кэш гильдий и
     * приглашения. Перезагрузка, которая распускает все пати на сервере, —
     * не перезагрузка, а скрытый рестарт.
     *
     * @return строки отчёта для того, кто позвал
     */
    List<String> reloadSettings() {
        reloadConfig();
        GuildsConfig fresh = GuildsConfig.fromMap(new HashMap<>(getConfig().getValues(true)));

        List<String> report = new ArrayList<>();
        // Про базу говорим отдельной строкой и только если её правда меняли:
        // иначе предупреждение звучало бы при каждой перезагрузке и его
        // перестали бы читать.
        if (!fresh.jdbcUrl().equals(config.jdbcUrl())
                || !fresh.tablePrefix().equals(config.tablePrefix())
                || fresh.poolSize() != config.poolSize()) {
            report.add("Настройки базы изменены — они применятся только после перезапуска сервера.");
        }

        this.config = fresh;
        guilds.applyConfig(fresh);
        parties.applyConfig(fresh.maxPartyMembers(), fresh.partyInviteTtl());
        sidebar.title(fresh.hudTitle());
        if (luckPermsBridge != null) {
            luckPermsBridge.applyConfig(fresh.luckPermsGroupPrefix(), fresh.suffixFormat());
        }
        restartHudTask();

        report.add("Настройки перечитаны.");
        report.addAll(bankStatus());
        return report;
    }

    private void housekeeping() {
        guilds.purgeInvites();
        guilds.purgeExpiredBonuses();
        int removed = parties.purgeIdle(
                getServer().getOnlinePlayers().stream()
                        .map(Player::getUniqueId)
                        .collect(Collectors.toSet()),
                PARTY_IDLE);
        if (removed > 0) getLogger().info("Распущено брошенных пати: " + removed);
    }

    /**
     * Честный отчёт при старте.
     *
     * Отдельными строками и с указанием, что именно выключено: «AurumGuilds
     * включён» без подробностей означал бы, что администратор узнает об
     * отсутствии суффиксов от игроков, а не из лога.
     */
    private void report(boolean luckPerms, boolean auth) {
        getLogger().info("Гильдии включены.");
        getLogger().info(luckPerms
                ? "LuckPerms найден: тег гильдии показывается суффиксом к нику."
                : "LuckPerms нет — суффиксы не работают. Всё остальное в гильдиях работает.");
        for (String line : bankStatus()) getLogger().info(line);
        getLogger().info(auth
                ? "AurumAuth найден: удаление аккаунта убирает игрока из гильдии автоматически."
                : "AurumAuth нет — убирать игроков придётся командой /guild admin remove.");
        if (Bukkit.getPluginManager().getPlugin("GladiatorArena") != null) {
            // Не поломка, а предупреждение о разделении слота — чтобы
            // «сайдбар пропал во время боя» не выглядело как ошибка.
            getLogger().info("Найдена GladiatorArena: во время боя сайдбар гильдий уступает ей "
                    + "слот и возвращается сам после боя.");
        }
    }
}
