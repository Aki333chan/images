package ovh.aurumgg.core.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PaymentRulesTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final PaymentRules.Limits LIMITS = new PaymentRules.Limits(
            true, new BigDecimal("1.00"), new BigDecimal("1000.00"), 30);

    @Test
    void выключенныеПереводыОтказываютДоВсехОстальныхПроверок() {
        PaymentRules rules = new PaymentRules();
        PaymentRules.Limits off = new PaymentRules.Limits(
                false, LIMITS.minimum(), LIMITS.maximum(), LIMITS.cooldownSeconds());

        // Сумма заведомо негодная: важно, что причина названа первая, а не любая
        // подходящая — человеку должно быть понятно, что чинить.
        PaymentRules.Verdict verdict = rules.begin(ALICE, BOB, new BigDecimal("999999"), off, 1_000L);

        assertFalse(verdict.allowed());
        assertEquals("payments-disabled", verdict.key());
    }

    @Test
    void платитьСебеНельзяИЭтоНеТратитЗадержку() {
        PaymentRules rules = new PaymentRules();

        assertEquals("pay-self", rules.begin(ALICE, ALICE, new BigDecimal("10"), LIMITS, 1_000L).key());
        // Отказ до занятия задержки: иначе опечатка в нике стоила бы человеку
        // тридцати секунд ожидания ни за что.
        assertTrue(rules.begin(ALICE, BOB, new BigDecimal("10"), LIMITS, 1_000L).allowed());
    }

    @Test
    void суммаВнеКоридораНазываетГраницы() {
        PaymentRules rules = new PaymentRules();

        PaymentRules.Verdict small = rules.begin(ALICE, BOB, new BigDecimal("0.50"), LIMITS, 1_000L);
        assertEquals("pay-limits", small.key());
        assertEquals("1", small.placeholders().get("minimum"));
        assertEquals("1000", small.placeholders().get("maximum"));

        assertEquals("pay-limits", rules.begin(ALICE, BOB, new BigDecimal("1000.01"), LIMITS, 1_000L).key());
    }

    @Test
    void задержкаНеПускаетВторойПереводИОтпускаетПоИстечении() {
        PaymentRules rules = new PaymentRules();
        assertTrue(rules.begin(ALICE, BOB, new BigDecimal("10"), LIMITS, 1_000L).allowed());

        PaymentRules.Verdict blocked = rules.begin(ALICE, BOB, new BigDecimal("10"), LIMITS, 11_000L);
        assertFalse(blocked.allowed());
        assertEquals("pay-cooldown", blocked.key());
        assertEquals("20", blocked.placeholders().get("seconds"));

        assertTrue(rules.begin(ALICE, BOB, new BigDecimal("10"), LIMITS, 31_001L).allowed());
    }

    @Test
    void задержкаЛичнаяАНеОбщая() {
        PaymentRules rules = new PaymentRules();
        assertTrue(rules.begin(ALICE, BOB, new BigDecimal("10"), LIMITS, 1_000L).allowed());
        assertTrue(rules.begin(BOB, ALICE, new BigDecimal("10"), LIMITS, 1_000L).allowed());
    }

    @Test
    void изДвадцатиОдновременныхПопытокПроходитРовноОдна() throws Exception {
        PaymentRules rules = new PaymentRules();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            // Одна и та же миллисекунда намеренно: именно здесь «посмотреть и
            // положить» пропустило бы вторую оплату.
            List<Callable<Boolean>> attempts = IntStream.range(0, 20)
                    .<Callable<Boolean>>mapToObj(i -> () ->
                            rules.begin(ALICE, BOB, new BigDecimal("10"), LIMITS, 1_000L).allowed())
                    .toList();
            long allowed = 0;
            for (Future<Boolean> future : executor.invokeAll(attempts)) {
                if (future.get()) allowed++;
            }
            assertEquals(1, allowed);
        } finally {
            executor.shutdownNow();
        }
    }
}
