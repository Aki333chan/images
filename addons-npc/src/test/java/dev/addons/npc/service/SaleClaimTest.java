package dev.addons.npc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Что должно пережить перезапуск между «забрали предметы» и «заплатили».
 *
 * Порядок шагов здесь обратный магазинному, и это не симметрия ради симметрии:
 * необратимая половина продажи — изъятие предметов, поэтому запись делается до
 * неё, а выплата идёт следом. Собьётся порядок — перезапущенный сервер либо
 * заплатит дважды, либо заберёт предметы и не заплатит.
 */
class SaleClaimTest {

    private static SaleClaim sale(List<String> commands) {
        return new SaleClaim("op-1", "ores", 7, 64, new BigDecimal("128.50"),
                1_700_000_000_000L, commands);
    }

    @Test
    void сначалаПредметыПотомДеньгиПотомКоманды() {
        SaleClaim claim = sale(List.of("say sold", "broadcast done"));

        assertEquals(4, claim.stepCount(), "предметы, выплата и две команды");
        assertTrue(claim.itemStep(0));
        assertTrue(claim.paymentStep(1));
        assertFalse(claim.paymentStep(0), "платить до изъятия нельзя");
        assertEquals(Optional.of("say sold"), claim.commandAt(2));
        assertEquals(Optional.of("broadcast done"), claim.commandAt(3));
        assertEquals(Optional.empty(), claim.commandAt(1));
        assertEquals(Optional.empty(), claim.commandAt(4));
    }

    @Test
    void ключВыплатыСтабиленИПривязанКОперации() {
        // Повтор выплаты после перезапуска обязан вернуться дубликатом, а не
        // заплатить второй раз. Держит это ключ, и он не должен меняться.
        assertEquals("npc-sale:op-1", sale(List.of()).paymentKey());
        assertEquals(sale(List.of()).paymentKey(), sale(List.of("say x")).paymentKey());
    }

    @Test
    void суммаНеТеряетТочностьПриЗаписи() {
        SaleClaim restored = SaleClaim.decode(sale(List.of("say sold")).encode()).orElseThrow();

        // Строкой, а не double: деньги, которые меняются при записи на диск, —
        // уже не деньги.
        assertEquals(0, new BigDecimal("128.50").compareTo(restored.payout()));
        assertEquals("op-1", restored.operation());
        assertEquals("ores", restored.buyerId());
        assertEquals(7, restored.slot());
        assertEquals(64, restored.amount());
        assertEquals(1_700_000_000_000L, restored.deadline());
        assertEquals(List.of("say sold"), restored.commands());
    }

    @Test
    void негодныйPayloadНеРазбирается() {
        // Обрыв записи или откат версии. Угадывать нельзя: на другом конце
        // либо предметы игрока, либо деньги сервера.
        assertEquals(Optional.empty(), SaleClaim.decode(""));
        assertEquals(Optional.empty(), SaleClaim.decode("operation: op\nbuyer: ores\namount: 0\npayout: 1\n"));
        assertEquals(Optional.empty(), SaleClaim.decode("operation: op\nbuyer: ores\namount: 5\npayout: 0\n"));
        assertEquals(Optional.empty(), SaleClaim.decode("operation: op\namount: 5\npayout: 1\n"));
        assertEquals(Optional.empty(), SaleClaim.decode(": не yaml вовсе ["));
    }

    @Test
    void описаниеДляАдминистратораНазываетСуммуИСкупщика() {
        String summary = sale(List.of("say sold")).summary();
        assertTrue(summary.contains("64 item"), summary);
        assertTrue(summary.contains("ores#7"), summary);
        assertTrue(summary.contains("128.5"), summary);
        assertTrue(summary.contains("1 command"), summary);
    }
}
