package dev.addons.npc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.addons.npc.model.GuildBonusType;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Гильдейский бонус: сначала деньги, потом выдача.
 *
 * Порядок здесь важнее, чем кажется. Выдай бонус первым — и авария до списания
 * оставит гильдию с усилением, которое никто не оплатил. Именно этой дырой и
 * объяснялась прежняя самодельная проверка «а не выдан ли уже» по подписи на
 * бонусе: она переставала работать, стоило бонусу истечь.
 */
class BonusClaimTest {

    private static BonusClaim bonus(long durationSeconds) {
        return new BonusClaim("npc-guild:op-1", "elders", 3, 42L, GuildBonusType.BLOCK_DROPS,
                1.25, durationSeconds, "AurumNPC:elders/Vasya#op-1");
    }

    @Test
    void сначалаДеньгиПотомВыдача() {
        BonusClaim claim = bonus(3600);

        assertEquals(2, claim.stepCount());
        assertTrue(claim.paymentStep(0));
        assertFalse(claim.grantStep(0), "выдавать до списания нельзя");
        assertTrue(claim.grantStep(1));
    }

    @Test
    void постоянныйБонусНеИмеетСрока() {
        // Границу задаёт API гильдий: null там означает «навсегда».
        assertNull(bonus(0).duration());
        assertEquals(Duration.ofSeconds(3600), bonus(3600).duration());
    }

    @Test
    void ключСписанияСтабиленИПривязанКРезерву() {
        // Повтор после перезапуска обязан вернуться дубликатом, а не списать
        // второй раз.
        assertEquals("npc-guild-claim:npc-guild:op-1", bonus(60).paymentKey());
        assertEquals(bonus(60).paymentKey(), bonus(120).paymentKey());
    }

    @Test
    void заявкаПереживаетКодированиеИРазбор() {
        BonusClaim restored = BonusClaim.decode(bonus(3600).encode()).orElseThrow();

        assertEquals("npc-guild:op-1", restored.holdKey());
        assertEquals("elders", restored.traderId());
        assertEquals(3, restored.slot());
        assertEquals(42L, restored.guildId());
        assertEquals(GuildBonusType.BLOCK_DROPS, restored.type());
        assertEquals(1.25, restored.magnitude());
        assertEquals(3600L, restored.durationSeconds());
        // Подпись нужна не для восстановления, а для журнала гильдии: там
        // должно быть видно, кто и по какой покупке выдал бонус.
        assertEquals("AurumNPC:elders/Vasya#op-1", restored.actor());
    }

    @Test
    void негодныйPayloadНеРазбирается() {
        assertEquals(Optional.empty(), BonusClaim.decode(""));
        // Вид бонуса, которого эта сборка не знает, — ровно тот случай, когда
        // догадка выдала бы гильдии не то усиление.
        assertEquals(Optional.empty(), BonusClaim.decode(
                "hold: h\ntrader: t\nguild: 1\ntype: WHAT_IS_THIS\n"));
        assertEquals(Optional.empty(), BonusClaim.decode(
                "trader: t\nguild: 1\ntype: BLOCK_DROPS\n"));
        assertEquals(Optional.empty(), BonusClaim.decode(": не yaml вовсе ["));
    }

    @Test
    void описаниеДляАдминистратораНазываетБонусИГильдию() {
        String summary = bonus(3600).summary();
        assertTrue(summary.contains("block_drops"), summary);
        assertTrue(summary.contains("guild 42"), summary);
        assertTrue(summary.contains("elders#3"), summary);
        assertTrue(bonus(0).summary().contains("permanent"), bonus(0).summary());
    }
}
