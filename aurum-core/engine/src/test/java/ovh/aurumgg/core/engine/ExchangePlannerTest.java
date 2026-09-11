package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.ExchangeRequest;

class ExchangePlannerTest {
    private static final CurrencySpec COINS = new CurrencySpec("coins", "Coins", "$", 2);
    private static final CurrencySpec TOKENS = new CurrencySpec("tokens", "Tokens", "T", 0);
    private static final AccountId PLAYER = AccountId.player(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    @Test
    void feeAndRoundingProduceBalancedMintBurnPostings() {
        ExchangeRule rule = rule(ExchangeSettlement.MINT_BURN);
        var quote = quote(rule);
        assertEquals(new BigDecimal("5.00"), quote.feeAmount());
        assertEquals(new BigDecimal("245.00"), quote.convertedAmount());
        assertEquals(new BigDecimal("2"), quote.targetAmount());
        var plan = ExchangePlanner.plan(request(rule, quote.expiresAt()), quote, rule.settlement());
        assertEquals(new BigDecimal("0.00"), sum(plan, "coins").setScale(2));
        assertEquals(BigDecimal.ZERO, sum(plan, "tokens").setScale(0));
        assertEquals(new BigDecimal("5.00"), plan.postings().get(
                new CurrencyAccountKey("coins", AccountId.globalTreasury())));
        assertEquals(new BigDecimal("-2"), plan.postings().get(new CurrencyAccountKey("tokens",
                new AccountId(AccountType.SYSTEM_SOURCE, "global"))));
    }

    @Test
    void reserveSettlementUsesSeparateCurrencyReserves() {
        ExchangeRule rule = rule(ExchangeSettlement.RESERVE);
        var quote = quote(rule);
        var plan = ExchangePlanner.plan(request(rule, quote.expiresAt()), quote, rule.settlement());
        AccountId reserve = new AccountId(AccountType.EXCHANGE_RESERVE, rule.id());
        assertEquals(new BigDecimal("245.00"),
                plan.postings().get(new CurrencyAccountKey("coins", reserve)));
        assertEquals(new BigDecimal("-2"),
                plan.postings().get(new CurrencyAccountKey("tokens", reserve)));
    }

    @Test
    void amountOutsideRuleRangeIsRejected() {
        assertThrows(PolicyRejectedException.class, () -> ExchangePlanner.quote(PLAYER,
                new BigDecimal("9.00"), rule(ExchangeSettlement.MINT_BURN), COINS, TOKENS,
                NOW, Duration.ofSeconds(30)));
    }

    private static ExchangeRule rule(ExchangeSettlement settlement) {
        return new ExchangeRule("coins-to-tokens", 3, "coins", "tokens",
                new BigDecimal("0.01"), new BigDecimal("0.02"), new BigDecimal("10.00"),
                new BigDecimal("1000.00"), settlement, Map.of(), 100, true, null, null);
    }

    private static ovh.aurumgg.core.api.ExchangeQuote quote(ExchangeRule rule) {
        return ExchangePlanner.quote(PLAYER, new BigDecimal("250.00"), rule,
                COINS, TOKENS, NOW, Duration.ofSeconds(30));
    }

    private static ExchangeRequest request(ExchangeRule rule, Instant expiresAt) {
        return new ExchangeRequest("exchange:test", PLAYER, "coins", "tokens",
                new BigDecimal("250.00"), rule.id(), rule.revision(), new BigDecimal("2"),
                expiresAt, Map.of());
    }

    private static BigDecimal sum(ExchangePlan plan, String currency) {
        return plan.postings().entrySet().stream().filter(it -> it.getKey().currencyId().equals(currency))
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
