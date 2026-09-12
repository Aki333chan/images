package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Правила перевода между игроками — те, что решаются до похода в ledger.
 *
 * <p>Здесь намеренно нет ни Bukkit, ни базы: включены ли переводы, не платит ли
 * человек сам себе, попадает ли сумма в разрешённый коридор и не слишком ли
 * часто он это делает — решается по числам и времени. Благодаря этому правила
 * проверяются тестом, а команда и игровой интерфейс спрашивают одно и то же
 * место вместо того, чтобы каждый носить свою копию условий.</p>
 *
 * <p><b>Задержка списывается за попытку, а не за успех.</b> Так было и в
 * команде: это защита от спама запросами, а не комиссия за перевод. Отменять
 * её при отказе ledger значило бы разрешить долбить сервер отказами.</p>
 */
final class PaymentRules {
    /** Больше этого числа ждущих — самое время выбросить истёкшие. */
    private static final int CLEANUP_THRESHOLD = 4_096;

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    /** Решение о переводе. `key` и `placeholders` — готовая строка для любого интерфейса. */
    record Verdict(boolean allowed, String key, Map<String, String> placeholders) {
        static Verdict allow() { return new Verdict(true, "", Map.of()); }
        static Verdict deny(String key) { return new Verdict(false, key, Map.of()); }
        static Verdict deny(String key, Map<String, String> placeholders) {
            return new Verdict(false, key, placeholders);
        }
    }

    /** Настройки перевода на момент проверки; берутся из конфига, а не хранятся здесь. */
    record Limits(boolean enabled, BigDecimal minimum, BigDecimal maximum, long cooldownSeconds) {}

    /**
     * Проверить перевод и, если он разрешён, сразу занять задержку.
     *
     * <p>Проверка и занятие — одно действие намеренно: между ними нельзя
     * оставлять щель, в которую пролезет второй запрос того же игрока.</p>
     */
    Verdict begin(UUID payer, UUID target, BigDecimal amount, Limits limits, long now) {
        if (!limits.enabled()) return Verdict.deny("payments-disabled");
        if (payer.equals(target)) return Verdict.deny("pay-self");
        if (amount.compareTo(limits.minimum()) < 0 || amount.compareTo(limits.maximum()) > 0) {
            return Verdict.deny("pay-limits", Map.of(
                    "minimum", plain(limits.minimum()), "maximum", plain(limits.maximum())));
        }
        if (cooldowns.size() > CLEANUP_THRESHOLD) {
            cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        // compute, а не «посмотреть и положить»: между чтением и записью не
        // должно быть щели, в которую пролезет второй запрос того же игрока.
        // Проверять по возвращённому значению нельзя — два запроса в одну и ту
        // же миллисекунду дали бы одинаковый срок и оба сошли бы за успешные.
        long[] blocked = {0L};
        cooldowns.compute(payer, (key, existing) -> {
            if (existing != null && existing > now) {
                blocked[0] = existing;
                return existing;
            }
            return now + limits.cooldownSeconds() * 1000L;
        });
        if (blocked[0] > now) {
            long seconds = Math.max(1, (blocked[0] - now + 999) / 1000);
            return Verdict.deny("pay-cooldown", Map.of("seconds", Long.toString(seconds)));
        }
        return Verdict.allow();
    }

    private static String plain(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
