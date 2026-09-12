package ovh.aurumgg.companion.core.model;

import java.util.List;

/**
 * Экономика сервера целиком.
 *
 * <h2>Откуда берутся числа</h2>
 *
 * Если на сервере активен AurumCore — из ledger, одним запросом: он и есть
 * источник истины, а казна, денежная масса и собранные налоги существуют
 * только там. Если Core нет — по-старому, обходом всех, кто когда-либо
 * заходил, через Vault.
 *
 * Разницу видно по {@link #ledger()}: пусто — значит считали по Vault, и
 * казны с налогами у такого сервера просто не существует. Панель показывает
 * то, что есть, а не пустые нули на месте того, чего нет.
 *
 * @param total          сколько всего денег панель видит: сумма кошельков у
 *                       Vault, денежная масса у ledger
 * @param playersCounted сколько игроков учтено; null у ledger — там сумма
 *                       берётся запросом, а не обходом, и считать игроков
 *                       незачем
 * @param top            доска богатства, уже отсортированная по убыванию
 * @param ledger         то, что бывает только у Core; null у Vault
 */
public record EconomySummary(
        double total, String totalFormatted, String currency, Integer playersCounted,
        List<TopEntry> top, Ledger ledger) {

    /** Строка доски богатства. */
    public record TopEntry(String name, String uuid, double balance, String formatted) {}

    /**
     * Величины, которые есть только у настоящего ledger.
     *
     * У Vault их не существует не потому, что мы их не считаем, а потому что
     * там нет ни казны сервера, ни понятия налога: деньги просто лежат в
     * кошельках.
     */
    public record Ledger(double treasury, String treasuryFormatted,
                         double moneySupply, String moneySupplyFormatted,
                         double taxesCollected, String taxesFormatted) {}
}
