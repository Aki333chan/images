package ovh.aurumgg.guilds.paper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.EconomyMode;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;
import ovh.aurumgg.guilds.core.BankResult;
import ovh.aurumgg.guilds.core.EconomyBridge;
import ovh.aurumgg.guilds.core.HudLines;

/**
 * Банк гильдии как настоящий счёт в ledger AurumCore.
 *
 * <h2>Что изменилось по сравнению с Vault</h2>
 *
 * За Vault счёта гильдии не существовало: банк был числом
 * {@code bank_balance} в нашей таблице, а «вклад» — двумя независимыми
 * шагами, снять с игрока и прибавить к числу. Между ними не было общей
 * транзакции, и падение ровно посередине означало деньги, которых нет ни
 * там, ни там.
 *
 * Здесь у гильдии есть адрес — {@code GUILD:<id>}, — и вклад со снятием стали
 * одной проводкой: либо она прошла целиком, либо не было ничего. Число в
 * нашей таблице после этого не источник правды, а зеркало для HUD и панели,
 * и после каждой проводки оно переспрашивается у ledger.
 *
 * <h2>Почему здесь можно блокироваться</h2>
 *
 * Методы AurumCore асинхронные, а {@link EconomyBridge} синхронный — и это
 * не недосмотр: зовут его из рабочего пула AurumGuilds, где операция с
 * гильдией и так идёт последовательно и уже ходит в MariaDB. Ждать ответа
 * там можно, в главном потоке — нельзя ни в коем случае, поэтому вызов из
 * главного потока отклоняется явно, а не «обычно успевает».
 *
 * <h2>Зависимость мягкая</h2>
 *
 * Классы {@code ovh.aurumgg.core.api.*} упоминаются только здесь, объект
 * создаётся лишь после {@link #installed()}. Нет AurumCore — класс не
 * загружается вовсе, и банк работает через {@code VaultBridge}.
 */
final class AurumCoreBridge implements EconomyBridge {

    static final String PLUGIN_NAME = "AurumCore";

    /**
     * Сколько ждать ответа ledger.
     *
     * Это не сетевой вызов, а поход в MariaDB внутри того же сервера, так что
     * пять секунд — это не «обычное время операции», а признак того, что с
     * базой что-то не так. Ждать дольше значило бы держать заблокированным
     * поток, на котором стоят в очереди все остальные операции с гильдиями.
     */
    private static final long TIMEOUT_SECONDS = 5;

    private final Plugin plugin;
    private volatile AurumEconomyApi api;

    AurumCoreBridge(Plugin plugin) {
        this.plugin = plugin;
        this.api = lookup(plugin);
    }

    /** Есть ли AurumCore и ведёт ли он экономику сам (а не в режиме наблюдателя). */
    static boolean installed(Plugin plugin) {
        return lookup(plugin) != null;
    }

    /**
     * Провайдера переспрашиваем, а не держим навсегда: AurumCore можно
     * перезагрузить на живом сервере, и ссылка на прежний экземпляр
     * означала бы тихо неработающие проводки. Стоимость — просмотр карты
     * сервисов, а зовут отсюда не чаще, чем игрок жмёт /guild bank.
     */
    private static AurumEconomyApi lookup(Plugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME) == null) return null;
        try {
            RegisteredServiceProvider<AurumEconomyApi> registration =
                    plugin.getServer().getServicesManager().getRegistration(AurumEconomyApi.class);
            AurumEconomyApi found = registration == null ? null : registration.getProvider();
            // PASSIVE означает, что деньгами на сервере распоряжается не Core,
            // а кто-то другой, и наши проводки туда не дойдут.
            return found != null && found.mode() == EconomyMode.ACTIVE ? found : null;
        } catch (NoClassDefFoundError | Exception e) {
            return null;
        }
    }

    @Override
    public boolean available() {
        api = lookup(plugin);
        return api != null;
    }

    @Override
    public boolean guildAccounts() {
        return true;
    }

    // ------------------------------------------------------------- проводки

    @Override
    public BankResult deposit(long guildId, UUID player, double amount) {
        return move(AccountId.player(player), guild(guildId), amount,
                TransactionCategory.GUILD_DEPOSIT,
                "guild-deposit:" + UUID.randomUUID(),
                guildId, player,
                // Отказ на пути «кошелёк игрока → банк» означает ровно одно:
                // у игрока столько нет.
                "guild.err.notEnoughMoney");
    }

    @Override
    public BankResult withdraw(long guildId, UUID player, double amount) {
        return move(guild(guildId), AccountId.player(player), amount,
                TransactionCategory.GUILD_WITHDRAWAL,
                "guild-withdraw:" + UUID.randomUUID(),
                guildId, player,
                // А здесь плательщик — сама гильдия. Если ledger отказал,
                // значит на счёте меньше, чем показывает наше зеркало.
                "guild.err.bankShort");
    }

    @Override
    public BankResult disburse(long guildId, UUID player, double amount, String key) {
        return move(guild(guildId), AccountId.player(player), amount,
                TransactionCategory.GUILD_WITHDRAWAL, key, guildId, player,
                "guild.err.bankShort");
    }

    @Override
    public BankResult toTreasury(long guildId, double amount, String key) {
        return move(guild(guildId), AccountId.globalTreasury(), amount,
                TransactionCategory.GUILD_WITHDRAWAL, key, guildId, null,
                "guild.err.bankShort");
    }

    @Override
    public BankResult seed(long guildId, double amount) {
        AurumEconomyApi current = api;
        if (current == null) return BankResult.unavailable();

        // SYSTEM_SOURCE — потому что до этой проводки у денег гильдии не было
        // владельца в ledger вовсе. Они не появляются: они уже существовали
        // как записанный остаток банка со своим журналом, и проводка только
        // даёт им адрес. Категория MIGRATION отделяет это от начислений.
        BankResult moved = move(
                new AccountId(AccountType.SYSTEM_SOURCE, "guild-bank-migration"),
                guild(guildId), amount, TransactionCategory.MIGRATION,
                "guild-bank-migration:" + guildId, guildId, null,
                "guild.err.economyRefused");
        return moved;
    }

    /** Идентификатор счёта гильдии. По внутреннему id, а не по тегу: тег меняется. */
    private static AccountId guild(long guildId) {
        return new AccountId(AccountType.GUILD, String.valueOf(guildId));
    }

    /**
     * Одна проводка и переспрошенный после неё баланс счёта гильдии.
     *
     * @param rejectedKey что сказать игроку, если ledger отклонил проводку:
     *                    смысл отказа зависит от того, кто здесь плательщик
     */
    private BankResult move(AccountId from, AccountId to, double amount,
            TransactionCategory category, String key, long guildId, UUID player,
            String rejectedKey) {
        AurumEconomyApi current = api;
        if (current == null) return BankResult.unavailable();
        if (Bukkit.isPrimaryThread()) {
            // Не «медленно», а недопустимо: это заморозило бы сервер всем.
            plugin.getLogger().severe("Попытка провести деньги гильдии из главного потока — "
                    + "операция отклонена. Это ошибка в коде, а не в настройках");
            return BankResult.unavailable();
        }

        CurrencySpec currency = current.primaryCurrency();
        BigDecimal value;
        try {
            // Округление до точности валюты симметрично: и списывается, и
            // зачисляется одна и та же величина, поэтому денег от него не
            // становится ни больше, ни меньше.
            value = currency.requireAmount(
                    BigDecimal.valueOf(amount).setScale(currency.scale(), RoundingMode.HALF_UP));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Сумма " + amount + " не годится для валюты "
                    + currency.id() + ": " + e.getMessage());
            return BankResult.refused();
        }

        Map<String, String> metadata = player == null
                ? Map.of("guild", String.valueOf(guildId))
                : Map.of("guild", String.valueOf(guildId), "player", player.toString());
        TransactionRequest request = new TransactionRequest(
                key, from, to, currency.id(), value, category, metadata);

        Optional<TransactionResult> answer = await(current.transfer(request), "проводка " + key);
        if (answer.isEmpty()) return BankResult.unavailable();

        TransactionResult result = answer.get();
        switch (result.status()) {
            case SUCCESS, DUPLICATE -> {
                warnAboutTax(result, category, key);
                return balance(current, guildId);
            }
            case REJECTED -> {
                // Текст от Core — в лог, а не игроку: это диагностика ledger,
                // и переводить её на язык сервера некому.
                plugin.getLogger().info("AurumCore отклонил проводку " + key + ": "
                        + result.message());
                return BankResult.fail(rejectedKey);
            }
            default -> {
                return BankResult.unavailable();
            }
        }
    }

    /**
     * Баланс счёта гильдии после проводки.
     *
     * Переспрашиваем, а не считаем сами: сумму мог уменьшить налог из policy
     * engine, и {@code TransactionResult} говорит о величинах проводки, но не
     * об остатке на счёте. Заодно это лечит расхождение зеркала, если
     * администратор двигал деньги гильдии командами AurumCore.
     *
     * Не ответил — пустой результат: вызывающий посчитает зеркало сам, и это
     * не ошибка, а всего лишь менее точное число на экране.
     */
    private BankResult balance(AurumEconomyApi current, long guildId) {
        Optional<Optional<BalanceSnapshot>> answer =
                await(current.balance(guild(guildId)), "баланс счёта гильдии " + guildId);
        return answer.flatMap(value -> value)
                .map(snapshot -> BankResult.success(snapshot.balance().doubleValue()))
                .orElseGet(BankResult::success);
    }

    /**
     * Налог на движение денег гильдии — почти наверняка не то, чего хотели.
     *
     * Policy engine выбирает правила по категории, и общесерверное правило
     * TAX вполне может задеть GUILD_DEPOSIT или MIGRATION. На переносе это
     * особенно скверно: часть уже существовавших денег исчезнет при простом
     * смене хранилища. Молча это не проходит.
     */
    private void warnAboutTax(TransactionResult result, TransactionCategory category, String key) {
        if (result.taxAmount() == null || result.taxAmount().signum() <= 0) return;
        plugin.getLogger().warning("На операцию " + category + " (" + key + ") подействовало "
                + "правило policy engine: удержано " + result.taxAmount() + " из "
                + result.grossAmount() + ". Проверьте правила AurumCore — на деньгах гильдий "
                + "это почти наверняка не то, чего хотели");
    }

    // ------------------------------------------------------------ служебное

    @Override
    public String format(double amount) {
        AurumEconomyApi current = api;
        String money = HudLines.money(amount);
        if (current == null) return money;
        String symbol = current.primaryCurrency().symbol();
        return symbol == null || symbol.isBlank() ? money : money + " " + symbol;
    }

    /** Дождаться ответа ledger. Пусто — значит не дождались, и это не успех. */
    private <T> Optional<T> await(CompletionStage<T> stage, String what) {
        try {
            return Optional.ofNullable(
                    stage.toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "AurumCore не ответил: " + what, e);
            return Optional.empty();
        }
    }
}
