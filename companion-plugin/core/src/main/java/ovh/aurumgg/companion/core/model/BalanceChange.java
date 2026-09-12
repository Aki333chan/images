package ovh.aurumgg.companion.core.model;

/**
 * Результат начисления или списания.
 *
 * Хранит баланс ДО и ПОСЛЕ: панель обязана записать в аудит именно пару
 * значений, а не только сумму — по ней потом видно, что операция реально
 * сделала, даже если провайдер округлил или упёрся в лимит.
 *
 * @param ok     прошла ли операция (EconomyResponse.transactionSuccess)
 * @param error  причина отказа от провайдера; null при успехе
 * @param before баланс до операции
 * @param after  баланс после (EconomyResponse.balance)
 */
public record BalanceChange(
        boolean ok,
        String code,
        String error,
        double before,
        double after,
        String formatted,
        String idempotencyKey,
        String source,
        boolean duplicate,
        Double expectedBalance,
        Double targetBalance,
        Double currentBalance) {

    /** Existing relative give/take result. */
    public BalanceChange(boolean ok, String code, String error, double before, double after,
                         String formatted, String idempotencyKey, String source, boolean duplicate) {
        this(ok, code, error, before, after, formatted, idempotencyKey, source, duplicate,
                null, null, after);
    }

    /** Compatibility constructor for a legacy Vault provider result. */
    public BalanceChange(boolean ok, String error, double before, double after, String formatted) {
        this(ok, ok ? "ok" : "rejected", error, before, after, formatted,
                null, "vault", false, null, null, after);
    }
}
