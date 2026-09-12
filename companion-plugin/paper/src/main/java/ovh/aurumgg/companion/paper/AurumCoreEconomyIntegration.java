package ovh.aurumgg.companion.paper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import ovh.aurumgg.companion.core.model.BalanceInfo;
import ovh.aurumgg.companion.core.model.BalanceChange;
import ovh.aurumgg.companion.core.model.BalanceMutation;
import ovh.aurumgg.companion.core.model.EconomySummary;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.EconomyMode;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Экономика сервера из ledger AurumCore.
 *
 * <h2>Чем это лучше прежнего счёта по Vault</h2>
 *
 * Vault-версия обходила ВСЕХ, кто когда-либо заходил, и спрашивала баланс
 * каждого по очереди — у провайдера это вполне может быть отдельный поход в
 * базу на игрока. Здесь всё то же самое берётся двумя запросами к ledger,
 * который и так считает эти величины.
 *
 * И главное: у Vault не существует ни казны сервера, ни денежной массы, ни
 * собранных налогов. Панель показывала сумму кошельков и называла её
 * экономикой сервера — теперь показывает то, что происходит на самом деле.
 *
 * <h2>Зависимость мягкая</h2>
 *
 * Классы {@code ovh.aurumgg.core.api.*} упоминаются только здесь, и объект
 * трогается лишь после проверки, что плагин на месте. Нет AurumCore — класс
 * не загружается вовсе, и всё считается по Vault, как раньше.
 */
final class AurumCoreEconomyIntegration {

    private static final String PLUGIN_NAME = "AurumCore";
    /** Ledger живёт в MariaDB рядом; дольше пары секунд — это уже неполадка. */
    private static final long TIMEOUT_SECONDS = 3;

    private final AurumEconomyApi economy;
    private final Function<UUID, String> playerName;

    /** Constructed by BukkitGameBridge on the main thread during onEnable. */
    AurumCoreEconomyIntegration(org.bukkit.plugin.Plugin plugin, Function<UUID, String> playerName) {
        this.playerName = playerName;
        this.economy = findApi(plugin);
    }

    /** Есть ли Core и ведёт ли он экономику сам, а не в режиме наблюдателя. */
    boolean active() {
        return economy != null;
    }

    private static AurumEconomyApi findApi(org.bukkit.plugin.Plugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME) == null) return null;
        try {
            RegisteredServiceProvider<AurumEconomyApi> registration =
                    plugin.getServer().getServicesManager().getRegistration(AurumEconomyApi.class);
            AurumEconomyApi found = registration == null ? null : registration.getProvider();
            // PASSIVE означает, что деньгами распоряжается кто-то другой, и
            // числа ledger в этом режиме описывают не тот сервер, который видят
            // игроки.
            return found != null && found.mode() == EconomyMode.ACTIVE ? found : null;
        } catch (NoClassDefFoundError | Exception unavailable) {
            return null;
        }
    }

    Optional<BalanceInfo> balance(UUID playerUuid) {
        if (economy == null) return Optional.empty();
        CurrencySpec currency = economy.primaryCurrency();
        return await(economy.balance(AccountId.player(playerUuid)))
                .flatMap(found -> found)
                .map(snapshot -> new BalanceInfo(snapshot.balance().doubleValue(),
                        format(snapshot.balance(), currency), currency.displayName()));
    }

    /** Native ledger mutation; an unavailable reply is never retried through Vault. */
    BalanceChange change(UUID playerUuid, BalanceMutation mutation) {
        CurrencySpec currency = mutation.currencyId().isBlank()
                ? economy.primaryCurrency() : economy.currency(mutation.currencyId()).orElse(null);
        if (currency == null) {
            return failed("currency-unknown", "Unknown currency", 0, mutation, "aurum");
        }
        AccountId player = AccountId.player(playerUuid);
        Optional<BalanceSnapshot> beforeSnapshot = await(economy.balance(player, currency.id()))
                .flatMap(found -> found);
        if (beforeSnapshot.isEmpty()) {
            return failed("unavailable", "Ledger balance unavailable", 0, mutation, "aurum");
        }
        BigDecimal before = beforeSnapshot.get().balance();
        AccountId system = new AccountId(
                mutation.operation() == BalanceMutation.Operation.GIVE
                        ? AccountType.SYSTEM_SOURCE : AccountType.SYSTEM_SINK,
                "global");
        TransactionRequest request = new TransactionRequest(
                "companion:" + mutation.idempotencyKey(),
                mutation.operation() == BalanceMutation.Operation.GIVE ? system : player,
                mutation.operation() == BalanceMutation.Operation.GIVE ? player : system,
                currency.id(), mutation.amount(), TransactionCategory.ADMIN_ADJUSTMENT,
                java.util.Map.of(
                        "actor", mutation.actor(),
                        "reason", mutation.reason(),
                        "source", "panel",
                        "operation", mutation.operation().name().toLowerCase(java.util.Locale.ROOT)));
        Optional<TransactionResult> answered = await(economy.transfer(request));
        if (answered.isEmpty() || answered.get().status() == TransactionResult.Status.UNAVAILABLE) {
            return failed("unavailable", "Ledger mutation unavailable", before.doubleValue(), mutation, "aurum");
        }
        TransactionResult result = answered.get();
        BigDecimal after = await(economy.balance(player, currency.id()))
                .flatMap(found -> found).map(BalanceSnapshot::balance).orElse(before);
        boolean success = result.status() == TransactionResult.Status.SUCCESS
                || result.status() == TransactionResult.Status.DUPLICATE;
        boolean duplicate = result.status() == TransactionResult.Status.DUPLICATE;
        String code = success ? (duplicate ? "duplicate" : "ok")
                : result.message().contains("IDEMPOTENCY_KEY_REUSED")
                        ? "idempotency-conflict" : "rejected";
        return new BalanceChange(success, code, success ? null : result.message(),
                before.doubleValue(), after.doubleValue(), format(after, currency),
                mutation.idempotencyKey(), "aurum", duplicate);
    }

    private static BalanceChange failed(String code, String error, double balance,
                                        BalanceMutation mutation, String source) {
        return new BalanceChange(false, code, error, balance, balance, null,
                mutation.idempotencyKey(), source, false);
    }

    /**
     * Казна, денежная масса, налоги и доска богатства.
     *
     * <p>Пустой ответ означает «спросить не удалось», а не «денег нет»: в этом
     * случае вызывающий честно откатывается на Vault, вместо того чтобы
     * показать в панели нули.
     */
    Optional<EconomySummary> summary(int topLimit) {
        if (economy == null) return Optional.empty();
        CurrencySpec currency = economy.primaryCurrency();

        Optional<GlobalEconomySnapshot> global = await(economy.globalSnapshot());
        if (global.isEmpty() || !global.get().available()) return Optional.empty();
        GlobalEconomySnapshot snapshot = global.get();

        List<EconomySummary.TopEntry> top = new ArrayList<>();
        for (BalanceSnapshot entry : await(economy.richest(currency.id(), topLimit)).orElse(List.of())) {
            UUID player = playerOf(entry.account());
            if (player == null) continue;
            String name = playerName.apply(player);
            top.add(new EconomySummary.TopEntry(
                    name == null ? player.toString() : name,
                    player.toString(),
                    entry.balance().doubleValue(),
                    format(entry.balance(), currency)));
        }

        EconomySummary.Ledger ledger = new EconomySummary.Ledger(
                snapshot.treasuryBalance().doubleValue(), format(snapshot.treasuryBalance(), currency),
                snapshot.moneySupply().doubleValue(), format(snapshot.moneySupply(), currency),
                snapshot.taxesCollected().doubleValue(), format(snapshot.taxesCollected(), currency));

        // playersCounted = null: сумма взята запросом, а не обходом игроков,
        // и считать их незачем. Ноль здесь был бы неправдой.
        return Optional.of(new EconomySummary(
                snapshot.moneySupply().doubleValue(), format(snapshot.moneySupply(), currency),
                currency.displayName(), null, List.copyOf(top), ledger));
    }

    private static UUID playerOf(AccountId account) {
        try {
            return UUID.fromString(account.reference());
        } catch (IllegalArgumentException notAPlayer) {
            return null;
        }
    }

    private static String format(BigDecimal amount, CurrencySpec currency) {
        String value = amount.stripTrailingZeros().toPlainString();
        String symbol = currency.symbol();
        return symbol == null || symbol.isBlank() ? value : value + " " + symbol;
    }

    /** Пусто — значит не дождались или упало; это НЕ «нулевой ответ». */
    private static <T> Optional<T> await(java.util.concurrent.CompletionStage<T> stage) {
        try {
            return Optional.ofNullable(stage.toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception failure) {
            return Optional.empty();
        }
    }
}
