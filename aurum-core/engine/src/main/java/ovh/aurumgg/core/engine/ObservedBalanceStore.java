package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.CurrencySpec;

public final class ObservedBalanceStore {
    private final Map<AccountId, BalanceSnapshot> balances = new ConcurrentHashMap<>();
    private final CurrencySpec currency;
    private final Clock clock;

    public ObservedBalanceStore(CurrencySpec currency, Clock clock) {
        this.currency = currency;
        this.clock = clock;
    }

    public void observe(AccountId account, BigDecimal balance) {
        balances.put(account, new BalanceSnapshot(account, currency, balance, Instant.now(clock), false));
    }

    public Optional<BalanceSnapshot> find(AccountId account) {
        return Optional.ofNullable(balances.get(account));
    }

    public int size() {
        return balances.size();
    }

    public void forget(AccountId account) {
        balances.remove(account);
    }
}
