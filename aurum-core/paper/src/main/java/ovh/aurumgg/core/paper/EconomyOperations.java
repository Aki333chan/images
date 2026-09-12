package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;

/**
 * Денежные операции игрока и администратора — один слой на все интерфейсы.
 *
 * <p>Команда, игровое окно AurumUI и всё, что появится дальше, обращаются
 * сюда. Иначе правила перевода (лимиты, задержка, запрет платить себе) и
 * разбор ответа ledger размножились бы по интерфейсам, и однажды один из них
 * начал бы отличаться — обычно тот, который правили последним.</p>
 *
 * <p>Результат возвращается ключом сообщения и подстановками, а не готовым
 * текстом: чат раскрасит его своим префиксом, окно покажет строкой состояния,
 * а перевод у них общий.</p>
 */
final class EconomyOperations {
    private final AurumCorePlugin plugin;
    private final PaymentRules payments = new PaymentRules();

    EconomyOperations(AurumCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Чем закончилась операция. `key` — ключ локализации, `placeholders` — его подстановки. */
    record Outcome(boolean success, String key, Map<String, String> placeholders) {
        static Outcome of(String key) { return new Outcome(false, key, Map.of()); }
        static Outcome of(String key, Map<String, String> placeholders) {
            return new Outcome(false, key, placeholders);
        }
        static Outcome ok(String key, Map<String, String> placeholders) {
            return new Outcome(true, key, placeholders);
        }
    }

    /**
     * Перевод между игроками.
     *
     * <p>Задержка занимается до похода в базу — см. {@link PaymentRules}: это
     * защита от спама, а не комиссия.</p>
     */
    CompletionStage<Outcome> pay(Player payer, OfflinePlayer target, BigDecimal amount, String reason) {
        if (!plugin.activeReady()) return done(Outcome.of(unavailableKey()));
        CurrencySpec currency = plugin.settings().currency();
        PaymentRules.Limits limits = new PaymentRules.Limits(plugin.settings().paymentsEnabled(),
                plugin.settings().paymentMinimum(), plugin.settings().paymentMaximum(),
                plugin.settings().paymentCooldownSeconds());
        PaymentRules.Verdict verdict = payments.begin(payer.getUniqueId(), target.getUniqueId(),
                amount, limits, System.currentTimeMillis());
        if (!verdict.allowed()) return done(Outcome.of(verdict.key(), verdict.placeholders()));

        TransactionRequest request = new TransactionRequest(
                "pay:" + UUID.randomUUID(),
                AccountId.player(payer.getUniqueId()),
                AccountId.player(target.getUniqueId()),
                currency.id(), amount, TransactionCategory.PLAYER_PAYMENT,
                metadata(payer.getName(), reason));

        return plugin.activeEconomy().transfer(request).thenApply(result -> {
            Outcome failure = failure(result);
            if (failure != null) return failure;
            // Получателю сообщаем сумму после налога: он получил именно её, и
            // увидеть в чате чужую «до» — верный способ прийти с вопросом.
            notifyRecipient(target, payer.getName(), result.netAmount(), currency);
            return Outcome.ok("pay-sent", Map.of(
                    "player", name(target), "amount", plain(amount), "symbol", currency.symbol()));
        }).exceptionally(error -> Outcome.of("money-unavailable"));
    }

    /** give, take и set — административная правка чужого счёта. */
    CompletionStage<Outcome> adjust(String actor, Adjustment operation, OfflinePlayer target,
                                    CurrencySpec currency, BigDecimal amount, String reason) {
        if (!plugin.activeReady()) return done(Outcome.of(unavailableKey()));
        AccountId account = AccountId.player(target.getUniqueId());
        String key = "admin:" + operation.id() + ":" + UUID.randomUUID();
        Map<String, String> metadata = metadata(actor, reason);

        CompletionStage<TransactionResult> future = operation == Adjustment.SET
                // set не переписывает баланс: он считает разницу и проводит её
                // через системный источник или сток, чтобы деньги не появлялись
                // и не исчезали мимо учёта.
                ? plugin.activeEconomy().setPlayerBalance(account, currency.id(), amount, key, metadata)
                : plugin.activeEconomy().transfer(new TransactionRequest(key,
                        operation == Adjustment.GIVE ? system(AccountType.SYSTEM_SOURCE) : account,
                        operation == Adjustment.GIVE ? account : system(AccountType.SYSTEM_SINK),
                        currency.id(), amount, TransactionCategory.ADMIN_ADJUSTMENT, metadata));

        return future.thenApply(result -> {
            Outcome failure = failure(result);
            if (failure != null) return failure;
            String balance = plugin.cachedBalance(account, currency.id())
                    .map(it -> plain(it.balance())).orElse("0");
            return Outcome.ok("economy-success", Map.of(
                    "operation", operation.id(), "player", name(target),
                    "amount", plain(amount), "balance", balance, "symbol", currency.symbol()));
        }).exceptionally(error -> Outcome.of("money-unavailable"));
    }

    /** Что именно делает администратор. */
    enum Adjustment {
        GIVE("give"), TAKE("take"), SET("set");

        private final String id;
        Adjustment(String id) { this.id = id; }
        String id() { return id; }

        static Optional<Adjustment> of(String raw) {
            for (Adjustment value : values()) {
                if (value.id.equalsIgnoreCase(raw)) return Optional.of(value);
            }
            return Optional.empty();
        }
    }

    /** Разбор суммы по правилам валюты. Пусто — сумма не годится, и причина одна и та же везде. */
    static Optional<BigDecimal> parseAmount(String raw, boolean allowZero, CurrencySpec currency) {
        try {
            BigDecimal value = currency.requireAmount(new BigDecimal(raw.trim()));
            if (value.signum() < 0 || (!allowZero && value.signum() == 0)) return Optional.empty();
            return Optional.of(value);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * Отказ ledger, переведённый в ключ сообщения; null — операция прошла.
     *
     * <p>Отказ политики отделён от нехватки денег намеренно: «у вас не хватает»
     * там, где на самом деле сработало правило, отправит человека искать
     * несуществующую пропажу.</p>
     */
    private Outcome failure(TransactionResult result) {
        if (result.status() == TransactionResult.Status.UNAVAILABLE) return Outcome.of("money-unavailable");
        if (result.status() == TransactionResult.Status.REJECTED) {
            return result.message().startsWith("POLICY:")
                    ? Outcome.of("policy-transaction-rejected",
                            Map.of("reason", result.message().substring("POLICY:".length())))
                    : Outcome.of("insufficient-funds");
        }
        return null;
    }

    private void notifyRecipient(OfflinePlayer target, String from, BigDecimal net, CurrencySpec currency) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = target.getPlayer();
            if (online == null) return;
            online.sendMessage(plugin.messages().component("pay-received", Map.of(
                    "player", from, "amount", plain(net), "symbol", currency.symbol())));
        });
    }

    private String unavailableKey() {
        return plugin.activeMode() ? "active-not-ready" : "active-only";
    }

    private static CompletionStage<Outcome> done(Outcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }

    private static AccountId system(AccountType type) { return new AccountId(type, "global"); }

    private static Map<String, String> metadata(String actor, String reason) {
        return Map.of("actor", actor, "reason", reason == null || reason.isBlank() ? "unspecified" : reason);
    }

    private static String name(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private static String plain(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
