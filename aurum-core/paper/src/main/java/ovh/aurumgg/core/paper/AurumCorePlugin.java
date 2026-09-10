package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
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
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.engine.PassiveEconomyService;
import ovh.aurumgg.core.engine.db.MariaDbManager;

public final class AurumCorePlugin extends JavaPlugin implements Listener {
    enum DatabaseState { DISABLED, STARTING, READY, FAILED }

    private CoreSettings settings;
    private LanguageBundle messages;
    private PassiveEconomyService economy;
    private BalanceObserver vault;
    private volatile DatabaseState databaseState = DatabaseState.DISABLED;
    private volatile MariaDbManager database;
    private ExecutorService databaseExecutor;

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
        if (!settings.configuredMode().equals("passive")) {
            getLogger().severe("AurumCore " + getPluginMeta().getVersion()
                    + " supports only economy.mode=passive. No economy provider was registered.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economy = new PassiveEconomyService(settings.currency(), Clock.systemUTC());
        vault = createBalanceObserver();
        getServer().getServicesManager().register(AurumEconomyApi.class, economy, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);

        AurumCommand command = new AurumCommand(this);
        var registered = getCommand("aurum");
        if (registered != null) {
            registered.setExecutor(command);
            registered.setTabCompleter(command);
        }
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new CorePlaceholderExpansion(this, economy).register();
        }
        getServer().getScheduler().runTaskTimer(this, this::refreshOnlineBalances, 1L, settings.refreshTicks());
        startDatabase();
        getLogger().info("Passive mode enabled. Existing Vault provider is observed, never modified: "
                + vault.providerName());
    }

    private void startDatabase() {
        if (!settings.databaseEnabled()) return;
        databaseState = DatabaseState.STARTING;
        databaseExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "AurumCore-Database-Init");
            thread.setDaemon(true);
            return thread;
        });
        databaseExecutor.execute(() -> {
            try {
                MariaDbManager opened = new MariaDbManager(settings.database());
                opened.migrate();
                if (!isEnabled()) {
                    opened.close();
                    return;
                }
                database = opened;
                databaseState = DatabaseState.READY;
                getLogger().info("MariaDB schema is ready. Passive mode still performs no monetary writes.");
            } catch (RuntimeException | java.sql.SQLException exception) {
                databaseState = DatabaseState.FAILED;
                getLogger().severe("MariaDB initialization failed: " + exception.getMessage());
            }
        });
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
        vault.balance(player).ifPresent(value -> economy.observe(AccountId.player(player.getUniqueId()), value));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onServiceRegistered(ServiceRegisterEvent event) {
        if (event.getProvider().getService().getName().equals("net.milkbowl.vault.economy.Economy")) {
            getServer().getScheduler().runTask(this, this::refreshOnlineBalances);
        }
    }

    void sendStatus(CommandSender sender) {
        sender.sendMessage(messages.component("status-header"));
        sender.sendMessage(messages.component("status-line", Map.of("key", "mode", "value", "PASSIVE")));
        sender.sendMessage(messages.component("status-line", Map.of("key", "vault", "value", vault.providerName())));
        sender.sendMessage(messages.component("status-line", Map.of(
                "key", "observed accounts", "value", Integer.toString(economy.observedAccountCount()))));
        sender.sendMessage(messages.component("status-line", Map.of(
                "key", "database", "value", databaseState.name())));
        sender.sendMessage(messages.component("status-line", Map.of("key", "writes", "value", "DISABLED")));
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        MariaDbManager current = database;
        if (current != null) current.close();
        if (databaseExecutor != null) {
            databaseExecutor.shutdownNow();
            try {
                databaseExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    PassiveEconomyService economy() { return economy; }
    LanguageBundle messages() { return messages; }
}
