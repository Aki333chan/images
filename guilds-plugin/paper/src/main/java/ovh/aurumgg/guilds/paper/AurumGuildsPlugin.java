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
import ovh.aurumgg.guilds.core.HelpBook;
import ovh.aurumgg.guilds.core.HudLines;
import ovh.aurumgg.guilds.core.Messages;
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
    private SocialUiProvider socialUi;

    public List<Map<String, String>> aurumSocialSnapshot(Player player, String scope) {
        return socialUi == null ? List.of() : socialUi.snapshot(player, scope);
    }

    public java.util.concurrent.CompletableFuture<String> aurumSocialAction(
            Player player, String id, String action, Map<String, String> arguments) {
        return socialUi.action(player, id, action, arguments);
    }
    private SidebarKeeper sidebar;
    /** Мост к LuckPerms или null — нужен перезагрузке, чтобы обновить формат суффикса. */
    private LuckPermsBridge luckPermsBridge;
    /** Мост к WorldGuard или null: без него дома гильдий просто недоступны. */
    private WorldGuardBridge worldGuard;
    /** Общая палка осмотра WorldGuard и выделения FAWE. */
    private RegionWandListener regionWand;
    /** Задача HUD: перезагрузка её пересоздаёт, если поменялся период. */
    private BukkitTask hudTask;
    /** Настройки на момент последней загрузки — с ними сверяется перезагрузка. */
    private GuildsConfig config;

    /**
     * Тексты для игроков. volatile: /guild admin reload меняет их на живом
     * сервере, а читают их и из планировщика сайдбара.
     */
    private volatile Messages messages;

    /**
     * Язык сервера как локаль Java — для дат и чисел.
     *
     * volatile рядом с messages и меняется вместе с ними: /guild admin reload
     * может сменить язык, и дата в /guild info обязана поехать за ним.
     */
    private volatile java.util.Locale locale = java.util.Locale.forLanguageTag("ru");

    java.util.Locale locale() {
        return locale;
    }

    /** Текст на языке сервера. */
    String text(String key) {
        return messages.get(key);
    }

    String text(String key, Map<String, String> values) {
        return messages.get(key, values);
    }

    java.util.List<String> lines(String key, Map<String, String> values) {
        return messages.list(key, values);
    }

    /** Подписи справки: «дальше» и счётчик страниц. */
    HelpBook.Labels helpLabels() {
        return new HelpBook.Labels(text("help.next"), text("help.counter"));
    }

    /** Подписи сайдбара — тем же способом, что и всё остальное. */
    HudLines.Labels hudLabels() {
        Messages current = messages;
        return current::get;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Map<String, Object> raw = new HashMap<>(getConfig().getValues(true));
        String language = Messages.normalizeLanguage(
                String.valueOf(raw.getOrDefault("language", Messages.DEFAULT_LANGUAGE)));
        messages = LanguageFiles.load(this, language);
        locale = java.util.Locale.forLanguageTag(language);
        Msg.use(this);
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
        // WorldGuard — четвёртая мягкая интеграция. Как и LuckPerms, решается
        // один раз: он регистрирует свои регионы в собственном onEnable, а
        // softdepend гарантирует, что тот пройдёт раньше нашего. Это не Vault:
        // здесь провайдер не приходит от третьего плагина.
        boolean worldGuardFound = WorldGuardBridge.installed();
        if (worldGuardFound) worldGuard = new WorldGuardBridge(getLogger());

        // Состав региона держится в согласии с составом гильдии тем же
        // механизмом, что и группы LuckPerms: обоим нужно знать о вступлении и
        // выходе, и оба узнают об этом одним и тем же вызовом.
        GuildHooks hooks = GuildHooks.composite(
                luckPerms ? luckPermsBridge : GuildHooks.noop(),
                worldGuardFound ? new RegionSyncHooks(this, () -> this.guilds, worldGuard) : GuildHooks.noop());

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

        // Словарь сервера — обоим сервисам. Не снимок, а ссылка на text():
        // /guild admin reload меняет messages, и оба должны увидеть новое сами.
        guilds.useLabels(this::text);
        parties.useLabels(this::text);

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
        if (worldGuardFound) {
            regionWand = new RegionWandListener(this, guilds, worldGuard);
            getServer().getPluginManager().registerEvents(regionWand, this);
        }
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

        report(luckPerms, auth, worldGuardFound);
    }

    @Override
    public void onDisable() {
        if (sidebar != null) {
            // Иначе у всех, кто сейчас в сети, останется висеть сайдбар,
            // который больше некому обновлять.
            for (Player player : getServer().getOnlinePlayers()) {
                sidebar.hide(player);
                player.removeMetadata("aurumui.party", this);
                player.removeMetadata("aurumui.guilds", this);
            }
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

        GuildCommand guildCommand = new GuildCommand(this, guilds, menu, worldGuard);
        guild.setExecutor(guildCommand);
        guild.setTabCompleter(guildCommand);

        PartyCommand partyCommand = new PartyCommand(parties);
        socialUi = new SocialUiProvider(this, guilds, parties);
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
            return List.of(text("guild.admin.bank.disabled"));
        }
        if (Bukkit.getPluginManager().getPlugin(VaultBridge.PLUGIN_NAME) == null) {
            return List.of(text("guild.admin.bank.noVault"));
        }
        if (!guilds.bankAvailable()) {
            // Две строки, поэтому список: одной строкой это не читается.
            return lines("guild.admin.bank.noProvider", java.util.Map.of());
        }
        return List.of(text("guild.admin.bank.ok"));
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
            for (Player player : getServer().getOnlinePlayers()) {
                sidebar.hide(player);
                player.removeMetadata("aurumui.party", this);
                player.removeMetadata("aurumui.guilds", this);
            }
            return;
        }
        long period = Math.max(1, config.hudRefresh().toMillis() / 50);
        hudTask = getServer().getScheduler().runTaskTimer(
                this, new HudTask(this, guilds, parties, sidebar), period, period);
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
        String language = Messages.normalizeLanguage(
                String.valueOf(getConfig().getValues(true)
                        .getOrDefault("language", Messages.DEFAULT_LANGUAGE)));
        messages = LanguageFiles.load(this, language);
        locale = java.util.Locale.forLanguageTag(language);
        GuildsConfig fresh = GuildsConfig.fromMap(new HashMap<>(getConfig().getValues(true)));

        List<String> report = new ArrayList<>();
        // Про базу говорим отдельной строкой и только если её правда меняли:
        // иначе предупреждение звучало бы при каждой перезагрузке и его
        // перестали бы читать.
        if (!fresh.jdbcUrl().equals(config.jdbcUrl())
                || !fresh.tablePrefix().equals(config.tablePrefix())
                || fresh.poolSize() != config.poolSize()) {
            report.add(text("guild.admin.reload.dbChanged"));
        }

        this.config = fresh;
        if (regionWand != null) regionWand.reload();
        guilds.applyConfig(fresh);
        parties.applyConfig(fresh.maxPartyMembers(), fresh.partyInviteTtl());
        sidebar.title(fresh.hudTitle());
        if (luckPermsBridge != null) {
            luckPermsBridge.applyConfig(fresh.luckPermsGroupPrefix(), fresh.suffixFormat());
        }
        restartHudTask();

        report.add(text("guild.admin.reload.done"));
        report.addAll(bankStatus());
        return report;
    }

    private void housekeeping() {
        if (socialUi != null) socialUi.purge();
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
    private void report(boolean luckPerms, boolean auth, boolean worldGuardFound) {
        getLogger().info("Гильдии включены.");
        getLogger().info(luckPerms
                ? "LuckPerms найден: тег гильдии показывается суффиксом к нику."
                : "LuckPerms нет — суффиксы не работают. Всё остальное в гильдиях работает.");
        for (String line : bankStatus()) getLogger().info(line);
        getLogger().info(auth
                ? "AurumAuth найден: удаление аккаунта убирает игрока из гильдии автоматически."
                : "AurumAuth нет — убирать игроков придётся командой /guild admin remove.");
        getLogger().info(worldGuardFound
                ? "WorldGuard найден: лидер может отдать свой регион гильдии — /guild claim."
                : "WorldGuard нет — домов гильдий не будет. Всё остальное в гильдиях работает.");
        if (Bukkit.getPluginManager().getPlugin("GladiatorArena") != null) {
            // Не поломка, а предупреждение о разделении слота — чтобы
            // «сайдбар пропал во время боя» не выглядело как ошибка.
            getLogger().info("Найдена GladiatorArena: во время боя сайдбар гильдий уступает ей "
                    + "слот и возвращается сам после боя.");
        }
    }
}
