package ovh.aurumgg.guilds.paper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Map<String, Object> raw = new HashMap<>(getConfig().getValues(true));
        GuildsConfig config = GuildsConfig.fromMap(raw);

        String requestedPrefix = String.valueOf(
                raw.getOrDefault("database.table-prefix", GuildsConfig.DEFAULT_PREFIX));
        if (!requestedPrefix.equals(config.tablePrefix())) {
            getLogger().warning("Префикс таблиц «" + requestedPrefix + "» не годится для SQL — "
                    + "используется «" + config.tablePrefix() + "»");
        }

        // --- необязательные соседи: решаем один раз, до касания их классов ---
        boolean luckPerms = LuckPermsBridge.installed();
        GuildHooks hooks = luckPerms
                ? new LuckPermsBridge(config.luckPermsGroupPrefix(), config.suffixFormat(), getLogger())
                : GuildHooks.noop();
        boolean vault = config.bankEnabled() && VaultBridge.installed();
        EconomyBridge economy = vault ? new VaultBridge() : EconomyBridge.unavailable();

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
        getServer().getPluginManager().registerEvents(new FriendlyFireListener(guilds), this);
        getServer().getPluginManager().registerEvents(new PlayerTracker(guilds, sidebar), this);

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

        if (config.hudEnabled()) {
            long period = Math.max(1, config.hudRefresh().toMillis() / 50);
            getServer().getScheduler().runTaskTimer(
                    this, new HudTask(guilds, parties, sidebar), period, period);
        }
        getServer().getScheduler().runTaskTimer(this, this::housekeeping,
                HOUSEKEEPING_TICKS, HOUSEKEEPING_TICKS);

        report(luckPerms, vault, auth);
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

    private void housekeeping() {
        guilds.purgeInvites();
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
    private void report(boolean luckPerms, boolean vault, boolean auth) {
        getLogger().info("Гильдии включены.");
        getLogger().info(luckPerms
                ? "LuckPerms найден: тег гильдии показывается суффиксом к нику."
                : "LuckPerms нет — суффиксы не работают. Всё остальное в гильдиях работает.");
        getLogger().info(vault
                ? "Vault найден: банк гильдии доступен."
                : "Vault нет — банк гильдии недоступен. Всё остальное в гильдиях работает.");
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
