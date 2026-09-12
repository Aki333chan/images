package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
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
import ovh.aurumgg.core.api.AurumClaimApi;
import ovh.aurumgg.core.api.AurumAuditApi;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;
import ovh.aurumgg.core.engine.LedgerEconomyService;
import ovh.aurumgg.core.engine.LedgerRepository;
import ovh.aurumgg.core.engine.ExchangeRegistry;
import ovh.aurumgg.core.engine.EconomyAuditService;
import ovh.aurumgg.core.engine.ExchangeRepository;
import ovh.aurumgg.core.engine.ExchangeService;
import ovh.aurumgg.core.engine.ClaimService;
import ovh.aurumgg.core.engine.HoldService;
import ovh.aurumgg.core.engine.TradeService;
import ovh.aurumgg.core.engine.MultiCurrencyEconomyService;
import ovh.aurumgg.core.engine.PassiveEconomyService;
import ovh.aurumgg.core.engine.PolicyRegistry;
import ovh.aurumgg.core.engine.PolicyRepository;
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
    private volatile MultiCurrencyEconomyService activeEconomy;
    private volatile AurumVaultEconomy vaultEconomy;
    private BalanceObserver vault;
    private volatile DatabaseState databaseState = DatabaseState.DISABLED;
    private volatile MariaDbManager database;
    private ExecutorService databaseExecutor;
    private volatile MigrationCoordinator migrations;
    private volatile PolicyRegistry policyRegistry;
    private volatile PolicyCoordinator policies;
    private volatile ExchangeRegistry exchangeRegistry;
    private volatile ExchangeCoordinator exchanges;
    private volatile ClaimService claims;
    private volatile EconomyAuditService audit;
    private volatile ClaimCoordinator claimCommands;
    private volatile ClaimsUiBridge claimsUi;
    private volatile TradeCoordinator tradeCommands;
    private volatile TradeDelivery tradeDelivery;
    private volatile TradeWindow tradeWindow;
    private final EconomyOperations economyOperations = new EconomyOperations(this);
    private final EconomyUiBridge economyUi = new EconomyUiBridge(this);

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
                "aurum", "abal", "atreasury", "amigrate", "aeco", "apolicy", "aexchange", "pay", "apay",
                "trade"}) {
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
                for (var currency : settings.currencies().values()) ledger.initialize(currency);
                if (!isEnabled()) {
                    opened.close();
                    return;
                }
                database = opened;
                MigrationRepository migrationRepository = opened.migrationRepository();
                initializePolicies(opened.policyRepository());
                initializeExchanges(opened.exchangeRepository());
                if (settings.configuredMode().equals("shadow")) {
                    migrations = new MigrationCoordinator(this, vault, settings.currency(), migrationRepository,
                            new MigrationService(settings.currency(), migrationRepository, ledger),
                            databaseExecutor, settings.migrationPlayersPerTick());
                    databaseState = DatabaseState.READY;
                    getLogger().info("MariaDB schema is ready. Migration tools are available; Vault remains live.");
                } else if (settings.configuredMode().equals("active")) {
                    validateActiveCutover(opened.stateRepository(), migrationRepository);
                    Map<String, LedgerEconomyService> services = new HashMap<>();
                    Object mutationLock = new Object();
                    for (var currency : settings.currencies().values()) {
                        LedgerEconomyService service = new LedgerEconomyService(currency, ledger,
                                policyRegistry, databaseExecutor, Clock.systemUTC(), mutationLock);
                        Map<AccountId, BigDecimal> seeds = new HashMap<>();
                        migrationRepository.allPlayerBalances(currency).forEach(row ->
                                seeds.put(AccountId.player(row.playerId()), row.balance()));
                        service.seedBalances(seeds);
                        services.put(currency.id(), service);
                    }
                    MultiCurrencyEconomyService service = new MultiCurrencyEconomyService(
                            settings.currency(), services);
                    var holdRepository = opened.holdRepository();
                    var claimRepository = opened.claimRepository();
                    service.attachHoldService(new HoldService(settings.currencies(), holdRepository,
                            service, databaseExecutor, Clock.systemUTC(), mutationLock,
                            Duration.ofSeconds(settings.holdMaxTtlSeconds())));
                    if (settings.exchange().enabled()) {
                        service.attachExchangeService(new ExchangeService(settings.currencies(), exchangeRegistry,
                                opened.exchangeRepository(), service, databaseExecutor, Clock.systemUTC(),
                                Duration.ofSeconds(settings.exchange().quoteTtlSeconds()), mutationLock));
                    }
                    // Claims live alongside holds and for the same reason: a hold
                    // protects the money half of an operation, a claim the half
                    // that happens in Minecraft. Both need the database, so both
                    // only exist in active mode.
                    claims = new ClaimService(claimRepository, databaseExecutor,
                            Clock.systemUTC(), Duration.ofSeconds(settings.claimMaxLeaseSeconds()),
                            settings.claimMaxAttempts());
                    audit = new EconomyAuditService(settings.currency(), settings.currencies(), ledger, holdRepository,
                            claimRepository, policyRegistry, exchangeRegistry, databaseExecutor, Clock.systemUTC());
                    claimCommands = new ClaimCoordinator(this, claims);
                    claimsUi = new ClaimsUiBridge(this, claims);
                    if (settings.tradingEnabled()) {
                        // Сделка опирается и на деньги, и на заявки: без заявок
                        // отданные предметы было бы некуда записать.
                        TradeService tradeService = new TradeService(opened.tradeRepository(), service,
                                databaseExecutor, Clock.systemUTC(),
                                Duration.ofSeconds(settings.tradeInviteTimeoutSeconds()),
                                Duration.ofSeconds(settings.tradeSessionTimeoutSeconds()));
                        tradeDelivery = new TradeDelivery(this, claims, messages);
                        tradeCommands = new TradeCoordinator(this, tradeService, tradeDelivery);
                        tradeWindow = new TradeWindow(this, tradeCommands);
                        tradeCommands.openWith(tradeWindow::show);
                    }
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

    private void initializePolicies(PolicyRepository repository) throws java.sql.SQLException {
        PolicyRegistry registry = new PolicyRegistry(settings.policies().enabled(), settings.currency().id());
        var stored = repository.list(settings.currency());
        if (stored.size() > settings.policies().maxRules()) {
            throw new java.sql.SQLException("Stored policy count exceeds financial-policies.max-rules");
        }
        for (var rule : stored) validatePolicyCurrencies(rule);
        if (stored.isEmpty() && settings.policies().bootstrapOnEmpty()
                && !settings.policies().bootstrapRules().isEmpty()) {
            repository.saveAll(settings.policies().bootstrapRules(), settings.currency(),
                    "config", "initial config bootstrap");
            stored = repository.list(settings.currency());
            for (var rule : stored) validatePolicyCurrencies(rule);
        }
        registry.replace(stored);
        policyRegistry = registry;
        policies = new PolicyCoordinator(this, repository, registry, databaseExecutor);
        getLogger().info("Loaded " + stored.size() + " financial policies; execution is "
                + (settings.policies().enabled() ? "enabled" : "disabled"));
    }

    private void validatePolicyCurrencies(ovh.aurumgg.core.engine.FinancialRule rule)
            throws java.sql.SQLException {
        String configured = rule.definition().get("currencies");
        if (configured == null || configured.isBlank() || configured.equals("*")) return;
        for (String id : configured.split(",")) {
            if (!settings.currencies().containsKey(id.trim().toLowerCase(java.util.Locale.ROOT))) {
                throw new java.sql.SQLException("Policy " + rule.id() + " uses unknown currency: " + id.trim());
            }
        }
    }

    private void initializeExchanges(ExchangeRepository repository) throws java.sql.SQLException {
        ExchangeRegistry registry = new ExchangeRegistry();
        var stored = repository.listRules(settings.currencies());
        if (stored.size() > settings.exchange().maxRules()) {
            throw new java.sql.SQLException("Stored exchange rule count exceeds exchange.max-rules");
        }
        if (stored.isEmpty() && settings.exchange().bootstrapOnEmpty()) {
            for (var rule : settings.exchange().bootstrapRules()) {
                repository.saveRule(rule, settings.currencies(), "config", "initial config bootstrap");
            }
            stored = repository.listRules(settings.currencies());
        }
        registry.replace(stored);
        exchangeRegistry = registry;
        exchanges = new ExchangeCoordinator(this, repository, registry, databaseExecutor);
        getLogger().info("Loaded " + stored.size() + " exchange rules; execution is "
                + (settings.exchange().enabled() ? "enabled" : "disabled"));
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
        // A separate service, not a corner of AurumEconomyApi: delivery is not
        // money, and a plugin that only delivers has no business holding an
        // interface that can move balances.
        if (claims != null) {
            getServer().getServicesManager().register(AurumClaimApi.class, claims,
                    this, ServicePriority.Highest);
        }
        if (audit != null) {
            getServer().getServicesManager().register(AurumAuditApi.class, audit,
                    this, ServicePriority.Highest);
        }
        if (tradeDelivery != null) {
            getServer().getPluginManager().registerEvents(tradeDelivery, this);
            getServer().getPluginManager().registerEvents(tradeCommands, this);
            getServer().getPluginManager().registerEvents(tradeWindow, this);
            // Один и тот же обход убирает брошенные столы и доводит доставку,
            // которую не удалось завершить сразу: у обоих один и тот же повод —
            // что-то осталось незакрытым.
            getServer().getScheduler().runTaskTimer(this,
                    () -> { if (tradeCommands != null) tradeCommands.sweep(); }, 200L, 200L);
        }
        vaultEconomy = new AurumVaultEconomy(this, activeEconomy.primaryService(), settings.currency());
        getServer().getServicesManager().register(Economy.class, vaultEconomy,
                this, ServicePriority.Highest);
        databaseState = DatabaseState.READY;
        registerPlaceholders();
        settings.currencies().keySet().forEach(activeEconomy::globalSnapshot);
        getServer().getScheduler().runTaskTimer(this,
                () -> settings.currencies().keySet().forEach(activeEconomy::globalSnapshot),
                settings.globalRefreshTicks(), settings.globalRefreshTicks());
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
        sender.sendMessage(messages.component("status-line", Map.of(
                "key", "policies", "value", policyRegistry == null ? "UNAVAILABLE"
                        : (settings.policies().enabled() ? "ENABLED:" : "DISABLED:")
                        + policyRegistry.snapshot().size())));
        sender.sendMessage(messages.component("status-line", Map.of(
                "key", "currencies", "value", Integer.toString(settings.currencies().size()))));
        sender.sendMessage(messages.component("status-line", Map.of(
                "key", "exchange", "value", exchangeRegistry == null ? "UNAVAILABLE"
                        : (settings.exchange().enabled() ? "ENABLED:" : "DISABLED:")
                        + exchangeRegistry.snapshot().size())));
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
    MultiCurrencyEconomyService activeEconomy() { return activeEconomy; }
    PolicyCoordinator policies() { return policies; }
    /** Денежные операции игрока и администратора; общий слой для команд и игрового окна. */
    EconomyOperations economyOperations() { return economyOperations; }

    /**
     * Снимок экономики для игрового окна AurumUI.
     *
     * <p>Вызывается Companion рефлексией — поэтому метод публичный и с точно
     * такой сигнатурой. Прямой зависимости между плагинами нет намеренно: Core
     * должен работать и на сервере, где Companion не установлен.</p>
     */
    public java.util.List<java.util.Map<String, String>> aurumEconomySnapshot(
            org.bukkit.entity.Player viewer, String scope) {
        return economyUi.snapshot(viewer, scope);
    }

    /**
     * Действие из игрового окна; возвращает ключ сообщения или его ожидание.
     *
     * <p>Права проверяются внутри по текущему состоянию игрока: то, что клиент
     * показал кнопку, ничего не значит.</p>
     */
    public Object aurumEconomyAction(org.bukkit.entity.Player viewer, String id, String action,
                                     java.util.Map<String, String> arguments) {
        return economyUi.action(viewer, id, action,
                arguments == null ? java.util.Map.of() : java.util.Map.copyOf(arguments));
    }

    /** Database-backed guaranteed-trade snapshot for AurumUI. */
    public Object aurumTradeSnapshot(org.bukkit.entity.Player viewer, String scope) {
        TradeCoordinator current = tradeCommands;
        return current == null ? java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of())
                : current.uiSnapshot(viewer);
    }

    /** Guaranteed-trade action; TradeCoordinator remains the only owner of its rules. */
    public Object aurumTradeAction(org.bukkit.entity.Player viewer, String id, String action,
                                   java.util.Map<String, String> arguments) {
        TradeCoordinator current = tradeCommands;
        if (current == null) return "error.trade.disabled";
        return current.uiAction(viewer, id, action,
                arguments == null ? java.util.Map.of() : java.util.Map.copyOf(arguments));
    }

    /** Quarantined delivery claims for AurumUI administrators. */
    public Object aurumClaimsSnapshot(org.bukkit.entity.Player viewer, String scope) {
        ClaimsUiBridge current = claimsUi;
        return current == null ? java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of())
                : current.snapshot(viewer);
    }

    /** Retry or deliberately drop one quarantined delivery claim. */
    public Object aurumClaimsAction(org.bukkit.entity.Player viewer, String id, String action,
                                    java.util.Map<String, String> arguments) {
        ClaimsUiBridge current = claimsUi;
        return current == null ? "error.claims.unavailable" : current.action(viewer, id, action);
    }
    ExchangeCoordinator exchanges() { return exchanges; }

    ClaimCoordinator claimCommands() {
        return claimCommands;
    }

    TradeCoordinator tradeCommands() {
        return tradeCommands;
    }
    Optional<BalanceSnapshot> cachedBalance(AccountId account) {
        if (activeEconomy != null) return activeEconomy.cachedBalance(account);
        return passiveEconomy == null ? Optional.empty() : passiveEconomy.cachedBalance(account);
    }
    Optional<BalanceSnapshot> cachedBalance(AccountId account, String currencyId) {
        if (activeEconomy != null) return activeEconomy.cachedBalance(account, currencyId);
        return settings.currency().id().equalsIgnoreCase(currencyId) ? cachedBalance(account) : Optional.empty();
    }
    GlobalEconomySnapshot cachedGlobalSnapshot() {
        if (activeEconomy != null) return activeEconomy.cachedGlobalSnapshot();
        BigDecimal zero = BigDecimal.ZERO.setScale(settings.currency().scale());
        return new GlobalEconomySnapshot(settings.currency(), zero, zero, zero,
                Instant.now(), false, false);
    }
    Optional<GlobalEconomySnapshot> cachedGlobalSnapshot(String currencyId) {
        if (activeEconomy == null || !settings.currencies().containsKey(currencyId.toLowerCase(java.util.Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(activeEconomy.cachedGlobalSnapshot(currencyId));
    }
    private int accountCount() {
        if (activeEconomy != null) return activeEconomy.cachedAccountCount();
        return passiveEconomy == null ? 0 : passiveEconomy.observedAccountCount();
    }
}
