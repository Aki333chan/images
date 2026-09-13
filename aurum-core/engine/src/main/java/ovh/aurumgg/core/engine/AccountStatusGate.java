package ovh.aurumgg.core.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.ManagedAccount;
import ovh.aurumgg.core.api.ManagedAccountMember;
import ovh.aurumgg.core.api.ManagedAccountStatus;
import ovh.aurumgg.core.api.TransactionCategory;

/** Lock-free lifecycle check on the hot payment path; database reads happen only on registry changes. */
public final class AccountStatusGate {
    private final ConcurrentHashMap<AccountId, ManagedAccountStatus> statuses = new ConcurrentHashMap<>();

    public AccountStatusGate(Map<AccountId, ManagedAccountStatus> initial) {
        statuses.putAll(initial);
    }

    public void update(ManagedAccount account) {
        for (ManagedAccountMember member : account.members()) statuses.put(member.account(), account.status());
    }

    String rejection(TransactionPlan plan) {
        for (LedgerPosting posting : plan.postings()) {
            String reason = rejection(posting.account(), posting.amount().signum(), plan.request().category());
            if (reason != null) return reason;
        }
        return null;
    }

    String rejection(ExchangePlan plan) {
        for (Map.Entry<CurrencyAccountKey, java.math.BigDecimal> posting : plan.postings().entrySet()) {
            String reason = rejection(posting.getKey().account(), posting.getValue().signum(), null);
            if (reason != null) return reason;
        }
        return null;
    }

    private String rejection(AccountId account, int sign, TransactionCategory category) {
        ManagedAccountStatus status = statuses.get(account);
        if (status == null || status == ManagedAccountStatus.ACTIVE) return null;
        if (status == ManagedAccountStatus.CLOSING && category == TransactionCategory.ACCOUNT_CLOSE && sign < 0) {
            return null;
        }
        return "ACCOUNT_STATUS:" + status.name() + ":" + account.stableKey();
    }
}
