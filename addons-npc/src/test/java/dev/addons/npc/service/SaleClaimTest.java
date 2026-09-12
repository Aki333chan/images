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

    private static List<ClaimCommand> commands(String... values) {
        return java.util.Arrays.stream(values).map(ClaimCommand::stored).toList();
    }

    private static SaleClaim sale(List<ClaimCommand> commands) {
        return new SaleClaim("op-1", "ores", 7, 64, new BigDecimal("128.50"),
                1_700_000_000_000L, commands);
    }

    @Test
    void сначалаПредметыПотомДеньгиПотомКоманды() {
        SaleClaim claim = sale(commands("say sold", "broadcast done"));

        assertEquals(4, claim.stepCount(), "предметы, выплата и две команды");
        assertTrue(claim.itemStep(0));
        assertTrue(claim.paymentStep(1));
        assertFalse(claim.paymentStep(0), "платить до изъятия нельзя");
        assertEquals(Optional.of(ClaimCommand.stored("say sold")), claim.commandAt(2));
        assertEquals(Optional.of(ClaimCommand.stored("broadcast done")), claim.commandAt(3));
        assertEquals(Optional.empty(), claim.commandAt(1));
        assertEquals(Optional.empty(), claim.commandAt(4));
    }

    @Test
    void ключВыплатыСтабиленИПривязанКОперации() {
        // Повтор выплаты после перезапуска обязан вернуться дубликатом, а не
        // заплатить второй раз. Держит это ключ, и он не должен меняться.
        assertEquals("npc-sale:op-1", sale(List.of()).paymentKey());
        assertEquals(sale(List.of()).paymentKey(), sale(commands("say x")).paymentKey());
    }

    @Test
    void режимКомандыСохраняетсяПослеЗаписиПродажи() {
        SaleClaim restored = SaleClaim.decode(sale(List.of(
                ClaimCommand.prepare("once:say sold", "sale:0"),
                ClaimCommand.prepare("idempotent:reward {idempotency_key}", "sale:1")))
                .encode()).orElseThrow();

        assertTrue(restored.commands().get(0).advanceBeforeEffect());
        assertFalse(restored.commands().get(1).advanceBeforeEffect());
        assertEquals("reward sale:1", restored.commands().get(1).command());
    }

    @Test
    void schemaОдинНеПереосмысливаетИсторическийПрефикс() {
        String legacy = "schema: 1\noperation: old\nbuyer: ores\nslot: 7\namount: 1\n"
                + "payout: '2.0'\ndeadline: 0\ncommands:\n  - 'idempotent:legacy literal'\n";

        ClaimCommand command = SaleClaim.decode(legacy).orElseThrow().commands().get(0);
        assertEquals(ClaimCommand.Mode.AT_MOST_ONCE, command.mode());
        assertEquals("idempotent:legacy literal", command.command());
    }

    @Test
    void суммаНеТеряетТочностьПриЗаписи() {
        SaleClaim restored = SaleClaim.decode(sale(commands("say sold")).encode()).orElseThrow();

        // Строкой, а не double: деньги, которые меняются при записи на диск, —
        // уже не деньги.
        assertEquals(0, new BigDecimal("128.50").compareTo(restored.payout()));
        assertEquals("op-1", restored.operation());
        assertEquals("ores", restored.buyerId());
        assertEquals(7, restored.slot());
        assertEquals(64, restored.amount());
        assertEquals(1_700_000_000_000L, restored.deadline());
        assertEquals(commands("say sold"), restored.commands());
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
        String summary = sale(commands("say sold")).summary();
        assertTrue(summary.contains("64 item"), summary);
        assertTrue(summary.contains("ores#7"), summary);
        assertTrue(summary.contains("128.5"), summary);
        assertTrue(summary.contains("1 command"), summary);
    }
}
