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
import ovh.aurumgg.companion.core.model.BalanceSetMutation;
import ovh.aurumgg.companion.core.model.EconomySummary;
import ovh.aurumgg.companion.core.model.EconomyAuditInfo;
import ovh.aurumgg.companion.core.model.EconomyRuleApply;
import ovh.aurumgg.companion.core.model.EconomyRuleInfo;
import ovh.aurumgg.companion.core.model.EconomyRuleMutation;
import ovh.aurumgg.companion.core.model.EconomyRulePreview;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.AurumAuditApi;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.AurumRulesAdminApi;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.BalanceSetRequest;
import ovh.aurumgg.core.api.BalanceSetResult;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.EconomyMode;
import ovh.aurumgg.core.api.EconomyAuditSection;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;
import ovh.aurumgg.core.api.RuleMutationRequest;
import ovh.aurumgg.core.api.RuleResource;
import ovh.aurumgg.core.api.RuleType;
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
    private final AurumAuditApi audit;
    private final AurumRulesAdminApi rules;
    private final Function<UUID, String> playerName;

    /** Constructed by BukkitGameBridge on the main thread during onEnable. */
    AurumCoreEconomyIntegration(org.bukkit.plugin.Plugin plugin, Function<UUID, String> playerName) {
        this.playerName = playerName;
        this.economy = findApi(plugin);
        this.audit = findAudit(plugin);
        this.rules = findRules(plugin);
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

    private static AurumAuditApi findAudit(org.bukkit.plugin.Plugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME) == null) return null;
        try {
            RegisteredServiceProvider<AurumAuditApi> registration =
                    plugin.getServer().getServicesManager().getRegistration(AurumAuditApi.class);
            return registration == null ? null : registration.getProvider();
        } catch (NoClassDefFoundError | Exception unavailable) {
            return null;
        }
    }

    private static AurumRulesAdminApi findRules(org.bukkit.plugin.Plugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME) == null) return null;
        try {
            RegisteredServiceProvider<AurumRulesAdminApi> registration =
                    plugin.getServer().getServicesManager().getRegistration(AurumRulesAdminApi.class);
            return registration == null ? null : registration.getProvider();
        } catch (NoClassDefFoundError | Exception unavailable) {
            return null;
        }
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

    /** Atomic absolute replacement. The expected value is never refreshed on retry. */
    BalanceChange set(UUID playerUuid, BalanceSetMutation mutation) {
        CurrencySpec currency = mutation.currencyId().isBlank()
                ? economy.primaryCurrency() : economy.currency(mutation.currencyId()).orElse(null);
        if (currency == null) {
            return new BalanceChange(false, "currency-unknown", "Unknown currency",
                    mutation.expectedBalance().doubleValue(), mutation.expectedBalance().doubleValue(), null,
                    mutation.idempotencyKey(), "aurum", false,
                    mutation.expectedBalance().doubleValue(), mutation.targetBalance().doubleValue(),
                    mutation.expectedBalance().doubleValue());
        }
        BalanceSetRequest request = new BalanceSetRequest(
                "companion:set:" + mutation.idempotencyKey(), AccountId.player(playerUuid), currency.id(),
                mutation.expectedBalance(), mutation.targetBalance(), java.util.Map.of(
                        "actor", mutation.actor(), "reason", mutation.reason(), "source", "panel"));
        Optional<BalanceSetResult> answered = await(economy.setBalance(request));
        if (answered.isEmpty() || answered.get().status() == BalanceSetResult.Status.UNAVAILABLE) {
            return new BalanceChange(false, "unavailable", "Ledger balance set unavailable",
                    mutation.expectedBalance().doubleValue(), mutation.expectedBalance().doubleValue(), null,
                    mutation.idempotencyKey(), "aurum", false,
                    mutation.expectedBalance().doubleValue(), mutation.targetBalance().doubleValue(),
                    mutation.expectedBalance().doubleValue());
        }
        BalanceSetResult result = answered.get();
        boolean success = result.status() == BalanceSetResult.Status.SUCCESS
                || result.status() == BalanceSetResult.Status.DUPLICATE;
        boolean duplicate = result.status() == BalanceSetResult.Status.DUPLICATE;
        String code = switch (result.status()) {
            case SUCCESS -> "ok";
            case DUPLICATE -> "duplicate";
            case CONFLICT -> "balance-conflict";
            case REJECTED -> result.message().contains("IDEMPOTENCY_KEY_REUSED")
                    ? "idempotency-conflict" : "rejected";
            case UNAVAILABLE -> "unavailable";
        };
        return new BalanceChange(success, code, success ? null : result.message(),
                result.balanceBefore().doubleValue(), result.balanceAfter().doubleValue(),
                format(result.currentBalance(), currency), mutation.idempotencyKey(), "aurum", duplicate,
                result.expectedBalance().doubleValue(), result.targetBalance().doubleValue(),
                result.currentBalance().doubleValue());
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

    Optional<EconomyAuditInfo> audit(
            String rawSection, String currencyId, String accountKey, int limit) {
        if (economy == null || audit == null) return Optional.empty();
        EconomyAuditSection section;
        try {
            section = EconomyAuditSection.valueOf(rawSection.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
        String selected = currencyId == null || currencyId.isBlank()
                ? economy.primaryCurrency().id() : currencyId.trim().toLowerCase(java.util.Locale.ROOT);
        return await(audit.read(section, selected, accountKey == null ? "" : accountKey.trim(),
                Math.clamp(limit, 1, 200)))
                .flatMap(value -> value)
                .map(page -> new EconomyAuditInfo(page.section().name().toLowerCase(java.util.Locale.ROOT),
                        page.currencyId(), page.generatedAt().toEpochMilli(), page.summary(),
                        page.records().stream().map(record ->
                                new EconomyAuditInfo.Record(record.type(), record.fields())).toList()));
    }

    Optional<List<EconomyRuleInfo>> rules(String rawType) {
        RuleType type = type(rawType);
        if (economy == null || rules == null || type == null) return Optional.empty();
        return await(rules.list(type)).flatMap(value -> value)
                .map(values -> values.stream().map(AurumCoreEconomyIntegration::rule).toList());
    }

    Optional<EconomyRulePreview> preview(EconomyRuleMutation mutation) {
        RuleType type = type(mutation.type());
        if (economy == null || rules == null || type == null) return Optional.empty();
        RuleMutationRequest request;
        try {
            request = new RuleMutationRequest(type, mutation.id(), mutation.expectedRevision(), mutation.fields());
        } catch (RuntimeException invalid) {
            return Optional.of(new EconomyRulePreview("invalid", "", null, null,
                    List.of(), invalid.getMessage(), 0));
        }
        return await(rules.preview(request, mutation.actor())).map(value -> new EconomyRulePreview(
                value.status().name().toLowerCase(java.util.Locale.ROOT), value.token(),
                value.current() == null ? null : rule(value.current()),
                value.proposed() == null ? null : rule(value.proposed()), value.warnings(), value.message(),
                value.expiresAt() == null ? 0 : value.expiresAt().toEpochMilli()));
    }

    Optional<EconomyRuleApply> apply(String token, String actor, String reason) {
        if (economy == null || rules == null) return Optional.empty();
        return await(rules.apply(token, actor, reason)).map(value -> new EconomyRuleApply(
                value.status().name().toLowerCase(java.util.Locale.ROOT),
                value.current() == null ? null : rule(value.current()), value.message()));
    }

    private static RuleType type(String raw) {
        try {
            return RuleType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static EconomyRuleInfo rule(RuleResource value) {
        return new EconomyRuleInfo(value.type().name().toLowerCase(java.util.Locale.ROOT),
                value.id(), value.revision(), value.fields());
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
