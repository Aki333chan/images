package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.ExchangeQuote;
import ovh.aurumgg.core.api.ExchangeRequest;

public final class ExchangePlanner {
    private ExchangePlanner() {}

    public static ExchangeQuote quote(AccountId account, BigDecimal rawAmount, ExchangeRule rule,
                                      CurrencySpec from, CurrencySpec to, Instant now, Duration ttl) {
        BigDecimal source = from.requireAmount(rawAmount);
        if (source.signum() <= 0 || rule.minimumSource() != null && source.compareTo(rule.minimumSource()) < 0
                || rule.maximumSource() != null && source.compareTo(rule.maximumSource()) > 0) {
            throw new PolicyRejectedException("Exchange amount is outside rule limits");
        }
        BigDecimal fee = source.multiply(rule.feeRate()).setScale(from.scale(), RoundingMode.HALF_UP);
        BigDecimal converted = source.subtract(fee);
        BigDecimal target = converted.multiply(rule.rate()).setScale(to.scale(), RoundingMode.DOWN);
        if (converted.signum() <= 0 || target.signum() <= 0) {
            throw new PolicyRejectedException("Exchange amount is too small after fee and rounding");
        }
        return new ExchangeQuote(rule.id(), rule.revision(), account, from, to, source, fee,
                converted, target, now, now.plus(ttl));
    }

    public static ExchangePlan plan(ExchangeRequest request, ExchangeQuote quote,
                                    ExchangeSettlement settlement) {
        if (!request.account().equals(quote.account())
                || !request.fromCurrencyId().equals(quote.fromCurrency().id())
                || !request.toCurrencyId().equals(quote.toCurrency().id())) {
            throw new IllegalArgumentException("Quote does not match exchange request");
        }
        AccountId sourceSettlement = settlement == ExchangeSettlement.MINT_BURN
                ? new AccountId(AccountType.SYSTEM_SINK, "global")
                : new AccountId(AccountType.EXCHANGE_RESERVE, quote.ruleId());
        AccountId targetSettlement = settlement == ExchangeSettlement.MINT_BURN
                ? new AccountId(AccountType.SYSTEM_SOURCE, "global")
                : new AccountId(AccountType.EXCHANGE_RESERVE, quote.ruleId());
        Map<CurrencyAccountKey, BigDecimal> postings = new LinkedHashMap<>();
        add(postings, quote.fromCurrency().id(), request.account(), quote.sourceAmount().negate());
        add(postings, quote.fromCurrency().id(), sourceSettlement, quote.convertedAmount());
        if (quote.feeAmount().signum() != 0) {
            add(postings, quote.fromCurrency().id(), AccountId.globalTreasury(), quote.feeAmount());
        }
        add(postings, quote.toCurrency().id(), targetSettlement, quote.targetAmount().negate());
        add(postings, quote.toCurrency().id(), request.account(), quote.targetAmount());
        return new ExchangePlan(request, quote, settlement, postings);
    }

    private static void add(Map<CurrencyAccountKey, BigDecimal> values, String currency,
                            AccountId account, BigDecimal amount) {
        values.merge(new CurrencyAccountKey(currency, account), amount, BigDecimal::add);
    }
}
