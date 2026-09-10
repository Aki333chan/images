package ovh.aurumgg.core.api;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface AurumEconomyApi {
    EconomyMode mode();

    CurrencySpec primaryCurrency();

    CompletionStage<Optional<BalanceSnapshot>> balance(AccountId account);

    CompletionStage<GlobalEconomySnapshot> globalSnapshot();

    CompletionStage<TransactionResult> transfer(TransactionRequest request);
}
