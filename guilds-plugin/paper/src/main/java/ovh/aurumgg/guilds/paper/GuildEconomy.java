package ovh.aurumgg.guilds.paper;

import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.guilds.core.BankResult;
import ovh.aurumgg.guilds.core.EconomyBridge;

/**
 * Какой именно экономикой пользуется банк гильдий — и когда это решается.
 *
 * <h2>Почему решение откладывается, а не принимается один раз на старте</h2>
 *
 * Ровно та же история, что была с Vault, и на ней уже обжигались. Провайдера
 * экономики регистрирует ТРЕТИЙ плагин, его onEnable вполне может пройти
 * позже нашего, и прежний код, спросив один раз и не увидев провайдера,
 * навсегда подставлял заглушку: банк оставался выключенным до перезапуска.
 * С AurumCore то же самое: его сервис появляется в его onEnable.
 *
 * Поэтому здесь два пути. На старте, если AurumCore уже поднялся, банк сразу
 * работает на ledger. Если нет — работает через Vault, а появление сервиса
 * AurumCore ловится событием.
 *
 * <h2>Переключение только в одну сторону</h2>
 *
 * Из Vault в ledger — да. Обратно — никогда, и это не упрощение.
 * После переноса деньги гильдий ЛЕЖАТ на счетах {@code GUILD:<id>}, а число
 * {@code bank_balance} в нашей таблице становится всего лишь зеркалом. Откат
 * к Vault означал бы, что банк снова работает по зеркалу — то есть выдаёт
 * игрокам деньги, которых на счёте уже нет. Если AurumCore выключили,
 * правильный ответ — «банк недоступен», а не «банк работает по старому
 * числу».
 *
 * <h2>Классы Core здесь не упоминаются</h2>
 *
 * Событие сравнивается с именем сервиса СТРОКОЙ. Обращение к
 * {@code AurumEconomyApi.class} загрузило бы классы Core на сервере, где их
 * нет, — а этого не должно случаться ни разу. Всё, что их трогает, заперто в
 * {@link AurumCoreBridge}, и туда мы заходим только убедившись, что плагин на
 * месте.
 */
final class GuildEconomy implements EconomyBridge, Listener {

    /** Полное имя интерфейса сервиса AurumCore — сравнивается как строка. */
    private static final String CORE_SERVICE = "ovh.aurumgg.core.api.AurumEconomyApi";

    private final Plugin plugin;
    /** Что сделать, когда банк переехал на ledger: перенести старые балансы. */
    private final Runnable onLedgerReady;
    private volatile EconomyBridge delegate;
    private volatile boolean ledger;

    GuildEconomy(Plugin plugin, EconomyBridge fallback, Runnable onLedgerReady) {
        this.plugin = plugin;
        this.delegate = fallback;
        this.onLedgerReady = onLedgerReady;
    }

    /**
     * Попробовать встать на AurumCore прямо сейчас.
     *
     * @return true, если банк теперь работает на ledger
     */
    boolean tryLedger() {
        if (ledger) return true;
        if (plugin.getServer().getPluginManager().getPlugin(AurumCoreBridge.PLUGIN_NAME) == null) {
            return false;
        }
        try {
            if (!AurumCoreBridge.installed(plugin)) return false;
            delegate = new AurumCoreBridge(plugin);
            ledger = true;
        } catch (NoClassDefFoundError error) {
            // Плагин с таким именем есть, а его API — не наше. Не повод падать.
            plugin.getLogger().warning("Плагин " + AurumCoreBridge.PLUGIN_NAME
                    + " найден, но его API не загружается: " + error.getMessage());
            return false;
        }
        plugin.getLogger().info("Банк гильдий работает на счетах AurumCore");
        onLedgerReady.run();
        return true;
    }

    /**
     * AurumCore поднялся позже нас — подхватываем сами.
     *
     * Событие приходит в главном потоке, а перенос балансов идёт в рабочем
     * пуле гильдий: {@code migrateBanks()} возвращает future и ничего здесь
     * не блокирует.
     */
    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (ledger) return;
        if (!CORE_SERVICE.equals(event.getProvider().getService().getName())) return;
        tryLedger();
    }

    // --------------------------------------------------------- делегирование

    @Override
    public boolean available() {
        return delegate.available();
    }

    @Override
    public boolean guildAccounts() {
        return delegate.guildAccounts();
    }

    @Override
    public BankResult deposit(long guildId, UUID player, double amount) {
        return delegate.deposit(guildId, player, amount);
    }

    @Override
    public BankResult withdraw(long guildId, UUID player, double amount) {
        return delegate.withdraw(guildId, player, amount);
    }

    @Override
    public BankResult disburse(long guildId, UUID player, double amount, String key) {
        return delegate.disburse(guildId, player, amount, key);
    }

    @Override
    public BankResult toTreasury(long guildId, double amount, String key) {
        return delegate.toTreasury(guildId, amount, key);
    }

    @Override
    public BankResult seed(long guildId, double amount) {
        return delegate.seed(guildId, amount);
    }

    @Override
    public String format(double amount) {
        return delegate.format(amount);
    }
}
