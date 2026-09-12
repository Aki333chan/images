package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.AurumAuditApi;
import ovh.aurumgg.core.api.ClaimSnapshot;
import ovh.aurumgg.core.api.ClaimStatus;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.EconomyAuditPage;
import ovh.aurumgg.core.api.EconomyAuditRecord;
import ovh.aurumgg.core.api.EconomyAuditSection;
import ovh.aurumgg.core.api.HoldSnapshot;

/** Read-only, sectioned projection for Companion and the web panel. */
public final class EconomyAuditService implements AurumAuditApi {
    private final Map<String, CurrencySpec> currencies;
    private final CurrencySpec primary;
    private final LedgerRepository ledger;
    private final HoldRepository holds;
    private final ClaimRepository claims;
    private final PolicyRegistry policies;
    private final ExchangeRegistry exchanges;
    private final Executor executor;
    private final Clock clock;

    public EconomyAuditService(CurrencySpec primary, Map<String, CurrencySpec> currencies, LedgerRepository ledger,
                               HoldRepository holds, ClaimRepository claims,
                               PolicyRegistry policies, ExchangeRegistry exchanges,
                               Executor executor, Clock clock) {
        this.primary = primary;
        this.currencies = Map.copyOf(currencies);
        this.ledger = ledger;
        this.holds = holds;
        this.claims = claims;
        this.policies = policies;
        this.exchanges = exchanges;
        this.executor = executor;
        this.clock = clock;
    }

    @Override
    public CompletionStage<Optional<EconomyAuditPage>> read(
            EconomyAuditSection section, String currencyId, String accountKey, int limit) {
        CurrencySpec currency = currency(currencyId);
        Optional<AccountId> account;
        try {
            account = account(accountKey);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (section == null || currency == null) return CompletableFuture.completedFuture(Optional.empty());
        int bounded = Math.clamp(limit, 1, 200);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Optional.of(switch (section) {
                    case OVERVIEW -> overview(currency);
                    case LEDGER -> ledger(currency, account, bounded);
                    case POLICIES -> policies(currency);
                    case EXCHANGES -> exchanges(currency);
                    case HOLDS -> holds(currency, bounded);
                    case CLAIMS -> claims(currency, bounded);
                });
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }, executor).exceptionally(failure -> Optional.empty());
    }

    private EconomyAuditPage overview(CurrencySpec currency) throws Exception {
        LedgerFlowAudit flow = ledger.flow(currency);
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("transactions", Long.toString(flow.transactions()));
        summary.put("turnover", amount(flow.turnover()));
        summary.put("issued", amount(flow.issued()));
        summary.put("sunk", amount(flow.sunk()));
        summary.put("netIssue", amount(flow.issued().subtract(flow.sunk())));
        summary.put("taxes", amount(flow.taxes()));
        summary.put("symbol", currency.symbol());
        return page(EconomyAuditSection.OVERVIEW, currency, summary, List.of());
    }

    private EconomyAuditPage ledger(CurrencySpec currency, Optional<AccountId> account, int limit) throws Exception {
        List<EconomyAuditRecord> records = ledger.history(currency, account, limit).stream().map(row -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("id", row.id().toString());
            fields.put("key", shorten(row.idempotencyKey(), 512));
            fields.put("category", row.category().name());
            fields.put("status", row.status());
            fields.put("gross", amount(row.gross()));
            fields.put("net", amount(row.net()));
            fields.put("tax", amount(row.tax()));
            fields.put("failure", shorten(row.failure(), 512));
            fields.put("metadata", shorten(row.metadata(), 1_500));
            fields.put("created", row.createdAt().toString());
            fields.put("committed", row.committedAt() == null ? "" : row.committedAt().toString());
            fields.put("postings", shorten(row.postings().stream().map(posting ->
                    posting.account().stableKey() + ":" + signed(posting.amount())
                            + "=" + amount(posting.balanceAfter())).collect(Collectors.joining(" | ")), 1_800));
            return new EconomyAuditRecord("transaction", fields);
        }).toList();
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("count", Integer.toString(records.size()));
        account.ifPresent(value -> summary.put("account", value.stableKey()));
        return page(EconomyAuditSection.LEDGER, currency, summary, records);
    }

    private EconomyAuditPage policies(CurrencySpec currency) {
        List<EconomyAuditRecord> records = policies.snapshot().stream().map(rule -> new EconomyAuditRecord(
                "policy", fields(
                "id", rule.id(), "kind", rule.kind().name(), "handler", Integer.toString(rule.handlerVersion()),
                "categories", shorten(rule.categories().stream().map(Enum::name).sorted()
                        .collect(Collectors.joining(",")), 1_800),
                "definition", shorten(pairs(rule.definition()), 1_800), "priority", Integer.toString(rule.priority()),
                "enabled", Boolean.toString(rule.enabled()), "active", Boolean.toString(rule.enabled()
                        && (rule.effectiveFrom() == null || !Instant.now(clock).isBefore(rule.effectiveFrom()))
                        && (rule.effectiveUntil() == null || Instant.now(clock).isBefore(rule.effectiveUntil()))),
                "from", instant(rule.effectiveFrom()), "until", instant(rule.effectiveUntil())))).toList();
        return page(EconomyAuditSection.POLICIES, currency, Map.of("count", Integer.toString(records.size())), records);
    }

    private EconomyAuditPage exchanges(CurrencySpec currency) {
        List<EconomyAuditRecord> records = exchanges.snapshot().stream().map(rule -> new EconomyAuditRecord(
                "exchange", fields(
                "id", rule.id(), "revision", Long.toString(rule.revision()),
                "fromCurrency", rule.fromCurrencyId(), "toCurrency", rule.toCurrencyId(),
                "rate", amount(rule.rate()), "feeRate", amount(rule.feeRate()),
                "minimum", amount(rule.minimumSource()), "maximum", amount(rule.maximumSource()),
                "settlement", rule.settlement().name(), "conditions", shorten(pairs(rule.conditions()), 1_800),
                "priority", Integer.toString(rule.priority()), "enabled", Boolean.toString(rule.enabled()),
                "active", Boolean.toString(rule.activeAt(Instant.now(clock))),
                "from", instant(rule.effectiveFrom()), "until", instant(rule.effectiveUntil())))).toList();
        return page(EconomyAuditSection.EXCHANGES, currency, Map.of("count", Integer.toString(records.size())), records);
    }

    private EconomyAuditPage holds(CurrencySpec currency, int limit) throws Exception {
        List<EconomyAuditRecord> records = holds.recent(currency, limit).stream().map(hold ->
                new EconomyAuditRecord("hold", fields(
                        "id", hold.id().toString(), "key", shorten(hold.idempotencyKey(), 512),
                        "from", hold.from().stableKey(), "to", hold.to().stableKey(),
                        "amount", amount(hold.amount()), "reserved", amount(hold.reservedAmount()),
                        "category", hold.category().name(), "purpose", hold.purpose(),
                        "reference", hold.referenceId(), "status", hold.status().name(),
                        "created", hold.createdAt().toString(), "expires", hold.expiresAt().toString(),
                        "metadata", shorten(pairs(hold.metadata()), 1_500)))).toList();
        long active = records.stream().filter(row -> "HELD".equals(row.fields().get("status"))).count();
        return page(EconomyAuditSection.HOLDS, currency,
                Map.of("count", Integer.toString(records.size()), "active", Long.toString(active)), records);
    }

    private EconomyAuditPage claims(CurrencySpec currency, int limit) throws Exception {
        List<EconomyAuditRecord> records = new ArrayList<>();
        for (ClaimSnapshot claim : claims.byStatus(ClaimStatus.QUARANTINED, "", limit)) {
            records.add(new EconomyAuditRecord("claim", fields(
                    "id", claim.id().toString(), "key", shorten(claim.idempotencyKey(), 512),
                    "plugin", claim.plugin(), "owner", claim.owner().toString(), "kind", claim.kind(),
                    "status", claim.status().name(), "progress", claim.stepCursor() + "/" + claim.stepCount(),
                    "attempts", Integer.toString(claim.attempts()), "summary", shorten(claim.summary(), 512),
                    "error", shorten(claim.lastError(), 512), "created", claim.createdAt().toString(),
                    "updated", claim.updatedAt().toString())));
        }
        return page(EconomyAuditSection.CLAIMS, currency,
                Map.of("count", Integer.toString(records.size())), records);
    }

    private EconomyAuditPage page(EconomyAuditSection section, CurrencySpec currency,
                                  Map<String, String> summary, List<EconomyAuditRecord> records) {
        return new EconomyAuditPage(section, currency.id(), summary, records, Instant.now(clock));
    }

    private CurrencySpec currency(String id) {
        if (id == null || id.isBlank()) return primary;
        return currencies.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    /** Empty means all accounts; malformed selectors fail closed instead of widening the query. */
    private static Optional<AccountId> account(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        int separator = key.indexOf(':');
        if (separator < 1 || separator == key.length() - 1) {
            throw new IllegalArgumentException("Malformed account key");
        }
        return Optional.of(new AccountId(
                AccountType.valueOf(key.substring(0, separator).toUpperCase(java.util.Locale.ROOT)),
                key.substring(separator + 1)));
    }

    private static Map<String, String> fields(String... values) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            fields.put(values[index], values[index + 1] == null ? "" : values[index + 1]);
        }
        return fields;
    }

    private static String pairs(Map<String, String> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(", "));
    }
    private static String amount(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }
    private static String signed(BigDecimal value) { return value.signum() > 0 ? "+" + amount(value) : amount(value); }
    private static String instant(Instant value) { return value == null ? "" : value.toString(); }
    private static String shorten(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
    }
}
