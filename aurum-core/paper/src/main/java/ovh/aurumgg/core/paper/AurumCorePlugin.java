package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;
import ovh.aurumgg.core.engine.LedgerEconomyService;
import ovh.aurumgg.core.engine.LedgerRepository;
import ovh.aurumgg.core.engine.PassiveEconomyService;
import ovh.aurumgg.core.engine.TaxRuleResolver;
import ovh.aurumgg.core.engine.db.MariaDbManager;
import ovh.aurumgg.core.engine.db.MariaDbStateRepository;
import ovh.aurumgg.core.engine.migration.MigrationRepository;
import ovh.aurumgg.core.engine.migration.MigrationService;

public final class AurumCorePlugin extends JavaPlugin implements Listener {
    enum DatabaseState { DISABLED, STARTING, READY, FAILED }

    private CoreSettings settings;
    private LanguageBundle messages;
    private volatile AurumEconomyApi economy;
    private PassiveEconomyService passiveEconomy;
    private volatile LedgerEconomyService activeEconomy;
    private volatile AurumVaultEconomy vaultEconomy;
    private BalanceObserver vault;
    private volatile DatabaseState databaseState = DatabaseState.DISABLED;
    private volatile MariaDbManager database;
    private ExecutorService databaseExecutor;
    private volatile MigrationCoordinator migrations;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = CoreSettings.read(getConfig());
        } catch (RuntimeException exception) {
            getLogger().severe("Invalid configuration: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        messages = new LanguageBundle(this, settings.language());
        if (!settings.configuredMode().equals("passive") && !settings.configuredMode().equals("shadow")
                && !settings.configuredMode().equals("active")) {
            getLogger().severe("AurumCore " + getPluginMeta().getVersion()
                    + " supports economy.mode=passive, shadow or active. No economy provider was registered.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!settings.configuredMode().equals("passive") && !settings.databaseEnabled()) {
            getLogger().severe("economy.mode=" + settings.configuredMode()
                    + " requires database.enabled=true. No money was changed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (settings.configuredMode().equals("active")
                && getServer().getPluginManager().getPlugin("Vault") == null
                && getServer().getPluginManager().getPlugin("VaultUnlocked") == null) {
            getLogger().severe("economy.mode=active requires Vault or VaultUnlocked.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        vault = createBalanceObserver();
        getServer().getPluginManager().registerEvents(this, this);

        AurumCommand command = new AurumCommand(this);
        for (String commandName : new String[] {
                "aurum", "abal", "atreasury", "amigrate", "aeco", "pay", "apay"}) {
            var registered = getCommand(commandName);
            if (registered != null) {
                registered.setExecutor(command);
                registered.setTabCompleter(command);
            }
        }
        if (!settings.configuredMode().equals("active")) {
            passiveEconomy = new PassiveEconomyService(settings.currency(), Clock.systemUTC());
            economy = passiveEconomy;
            getServer().getServicesManager().register(AurumEconomyApi.class, economy, this, ServicePriority.Normal);
            registerPlaceholders();
            getServer().getScheduler().runTaskTimer(this, this::refreshOnlineBalances,
                    1L, settings.refreshTicks());
        }
        startDatabase();
        getLogger().info(settings.configuredMode() + " mode is starting. Vault provider: " + vault.providerName());
    }

    private void startDatabase() {
        if (!settings.databaseEnabled()) return;
        databaseState = DatabaseState.STARTING;
        databaseExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "AurumCore-Database-Init");
            thread.setDaemon(true);
            return thread;
        });
        Runnable initializer = () -> {
            try {
                MariaDbManager opened = new MariaDbManager(settings.database());
                opened.migrate();
                LedgerRepository ledger = opened.ledgerRepository(Clock.systemUTC());
                ledger.initialize(settings.currency());
                if (!isEnabled()) {
                    opened.close();
                    return;
                }
                database = opened;
                MigrationRepository migrationRepository = opened.migrationRepository();
                if (settings.configuredMode().equals("shadow")) {
                    migrations = new MigrationCoordinator(this, vault, settings.currency(), migrationRepository,
                            new MigrationService(settings.currency(), migrationRepository, ledger),
                            databaseExecutor, settings.migrationPlayersPerTick());
                    databaseState = DatabaseState.READY;
                    getLogger().info("MariaDB schema is ready. Migration tools are available; Vault remains live.");
                } else if (settings.configuredMode().equals("active")) {
                    validateActiveCutover(opened.stateRepository(), migrationRepository);
                    LedgerEconomyService service = new LedgerEconomyService(settings.currency(), ledger,
                            TaxRuleResolver.none(), databaseExecutor, Clock.systemUTC());
                    Map<AccountId, BigDecimal> seeds = new HashMap<>();
                    migrationRepository.allPlayerBalances(settings.currency()).forEach(row ->
                            seeds.put(AccountId.player(row.playerId()), row.balance()));
                    service.seedBalances(seeds);
                    activeEconomy = service;
                    economy = service;
                    if (getServer().isPrimaryThread()) enableActiveServices();
                    else getServer().getScheduler().runTask(this, this::enableActiveServices);
                } else {
                    databaseState = DatabaseState.READY;
                    getLogger().info("MariaDB schema is ready. Passive mode performs no monetary writes.");
                }
            } catch (RuntimeException | java.sql.SQLException exception) {
                databaseState = DatabaseState.FAILED;
                getLogger().severe("MariaDB initialization failed: " + exception.getMessage());
                if (settings.configuredMode().equals("active")) {
                    if (getServer().isPrimaryThread()) getServer().getPluginManager().disablePlugin(this);
                    else getServer().getScheduler().runTask(this,
                                () -> getServer().getPluginManager().disablePlugin(this));
                }
            }
        };
        // Active startup is synchronous so later Vault consumers never cache the legacy provider.
        if (settings.configuredMode().equals("active")) initializer.run();
        else databaseExecutor.execute(initializer);
    }

    private void validateActiveCutover(MariaDbStateRepository state, MigrationRepository migrationRepository)
            throws java.sql.SQLException {
        if (state.get("active_cutover").isPresent()) return;
        if (!settings.requireVerifiedMigration()) {
            boolean hasMoney = migrationRepository.allPlayerBalances(settings.currency()).stream()
                    .anyMatch(row -> row.balance().signum() != 0);
            if (hasMoney) {
                throw new java.sql.SQLException("Unverified active startup is allowed only for an empty ledger");
            }
            state.put("active_cutover", "fresh:" + Instant.now());
            getLogger().warning("Active mode started without a migration gate because "
                    + "active.require-verified-migration=false.");
            return;
        }
        var verified = migrationRepository.latestVerified(settings.currency())
                .orElseThrow(() -> new java.sql.SQLException(
                        "Active mode requires a VERIFIED migration run; return to shadow mode"));
        var refreshed = migrationRepository.refreshComparison(verified.runId(), settings.currency());
        if (!refreshed.status().equals("VERIFIED")) {
            throw new java.sql.SQLException("Verified migration no longer matches the ledger");
        }
        state.put("active_cutover", "migration:" + verified.runId());
    }

    private void enableActiveServices() {
        if (!isEnabled() || activeEconomy == null) return;
        getServer().getServicesManager().register(AurumEconomyApi.class, activeEconomy,
                this, ServicePriority.Highest);
        vaultEconomy = new AurumVaultEconomy(this, activeEconomy, settings.currency());
        getServer().getServicesManager().register(Economy.class, vaultEconomy,
                this, ServicePriority.Highest);
        databaseState = DatabaseState.READY;
        registerPlaceholders();
        activeEconomy.globalSnapshot();
        getServer().getScheduler().runTaskTimer(this,
                () -> activeEconomy.globalSnapshot(), settings.globalRefreshTicks(), settings.globalRefreshTicks());
        getLogger().info("Active economy is authoritative. AurumCore registered as the highest-priority Vault provider.");
    }

    private void registerPlaceholders() {
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new CorePlaceholderExpansion(this).register();
        }
    }

    private BalanceObserver createBalanceObserver() {
        boolean installed = getServer().getPluginManager().getPlugin("Vault") != null
                || getServer().getPluginManager().getPlugin("VaultUnlocked") != null;
        if (!installed) return new UnavailableBalanceObserver();
        try {
            return new VaultObserver(this, settings.currency());
        } catch (LinkageError error) {
            getLogger().warning("Vault plugin was detected but its Economy API could not be loaded: "
                    + error.getClass().getSimpleName());
            return new UnavailableBalanceObserver();
        }
    }

    private void refreshOnlineBalances() {
        for (Player player : getServer().getOnlinePlayers()) refresh(player);
    }

    private void refresh(Player player) {
        if (passiveEconomy != null) {
            vault.balance(player).ifPresent(value ->
                    passiveEconomy.observe(AccountId.player(player.getUniqueId()), value));
        } else if (activeEconomy != null) {
            activeEconomy.cacheZeroIfAbsent(AccountId.player(player.getUniqueId()));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onServiceRegistered(ServiceRegisterEvent event) {
        if (passiveEconomy != null
                && event.getProvider().getService().getName().equals("net.milkbowl.vault.economy.Economy")) {
            getServer().getScheduler().runTask(this, this::refreshOnlineBalances);
        }
    }

    void sendStatus(CommandSender sender) {
        sender.sendMessage(messages.component("status-header"));
        sender.sendMessage(messages.component("status-line", Map.of(
                "key", "mode", "value", settings.configuredMode().toUpperCase(java.util.Locale.ROOT))));
        sender.sendMessage(messages.component("status-line", Map.of("key", "vault", "value",
                activeReady() ? "AurumCore" : vault.providerName())));
        sender.sendMessage(messages.component("status-line", Map.of(
                "key", "cached accounts", "value", Integer.toString(accountCount()))));
        sender.sendMessage(messages.component("status-line", Map.of(
                "key", "database", "value", databaseState.name())));
        sender.sendMessage(messages.component("status-line", Map.of("key", "writes", "value",
                settings.configuredMode().equals("active") && activeReady() ? "ENABLED"
                        : settings.configuredMode().equals("shadow") ? "MIGRATION_LEDGER_ONLY" : "DISABLED")));
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        MigrationCoordinator currentMigrations = migrations;
        if (currentMigrations != null) currentMigrations.close();
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
            try {
                databaseExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            if (!databaseExecutor.isTerminated()) databaseExecutor.shutdownNow();
        }
        MariaDbManager current = database;
        if (current != null) current.close();
    }

    LanguageBundle messages() { return messages; }
    boolean shadowMode() { return settings.configuredMode().equals("shadow"); }
    boolean activeMode() { return settings.configuredMode().equals("active"); }
    boolean activeReady() { return activeMode() && databaseState == DatabaseState.READY && activeEconomy != null; }
    MigrationCoordinator migrations() { return migrations; }
    CoreSettings settings() { return settings; }
    LedgerEconomyService activeEconomy() { return activeEconomy; }
    Optional<BalanceSnapshot> cachedBalance(AccountId account) {
        if (activeEconomy != null) return activeEconomy.cachedBalance(account);
        return passiveEconomy == null ? Optional.empty() : passiveEconomy.cachedBalance(account);
    }
    GlobalEconomySnapshot cachedGlobalSnapshot() {
        if (activeEconomy != null) return activeEconomy.cachedGlobalSnapshot();
        BigDecimal zero = BigDecimal.ZERO.setScale(settings.currency().scale());
        return new GlobalEconomySnapshot(settings.currency(), zero, zero, zero,
                Instant.now(), false, false);
    }
    private int accountCount() {
        if (activeEconomy != null) return activeEconomy.cachedAccountCount();
        return passiveEconomy == null ? 0 : passiveEconomy.observedAccountCount();
    }
}
