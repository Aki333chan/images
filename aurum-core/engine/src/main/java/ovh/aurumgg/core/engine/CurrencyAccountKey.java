package ovh.aurumgg.core.engine;

import java.util.Locale;
import java.util.Objects;
import ovh.aurumgg.core.api.AccountId;

public record CurrencyAccountKey(String currencyId, AccountId account) {
    public CurrencyAccountKey {
        currencyId = Objects.requireNonNull(currencyId, "currencyId").toLowerCase(Locale.ROOT);
        Objects.requireNonNull(account, "account");
    }
}
