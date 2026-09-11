package org.ChisaO_o.gladiatorArena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.HoldSnapshot;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;

/**
 * Совпадение резерва и capture.
 *
 * ЗАЧЕМ ЭТО ЗАКРЕПЛЕНО ТЕСТОМ. Core сверяет резерв и запрос на фиксацию по
 * шести полям сразу, включая metadata ЦЕЛИКОМ (см. HoldService.sameIntent и
 * docs/aurum-holds.md). Расхождение не ломает сборку и не видно в коде: оно
 * проявляется отказом capture уже после того, как деньги игрока
 * зарезервированы. Лишний ключ в одной из двух карт — ровно такая ошибка, и
 * она уже была допущена при первой версии этого слоя.
 */
class HoldIntentRegressionTest {

    private static final CurrencySpec CURRENCY = new CurrencySpec("gold", "Золото", "з", 2);

    /** Резерв, каким его вернул бы Core на запрос сервиса. */
    private static HoldSnapshot heldAs(UUID operation, UUID player, String arena,
                                       BetTicket.Purpose purpose, BigDecimal amount) {
        return new HoldSnapshot(UUID.randomUUID(), "arena-" + purpose.name().toLowerCase(java.util.Locale.ROOT)
                + ":" + operation,
                AccountId.player(player),
                new AccountId(AccountType.ARENA_ESCROW,
                        purpose.name().toLowerCase(java.util.Locale.ROOT) + ":" + arena),
                CURRENCY, amount, amount, TransactionCategory.ARENA_BET,
                purpose == BetTicket.Purpose.BET ? "arena-bet" : "arena-final", arena,
                HoldSnapshot.Status.HELD, Instant.now(), Instant.now().plusSeconds(120),
                BetTicket.metadata(operation, arena, purpose));
    }

    @Test
    @DisplayName("Capture ставки повторяет намерение резерва по всем сверяемым полям")
    void captureMatchesHold() {
        UUID operation = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        HoldSnapshot hold = heldAs(operation, player, "colosseum", BetTicket.Purpose.BET,
                new BigDecimal("10.00"));

        TransactionRequest capture = BetTicket.of(operation, player, "colosseum",
                BetTicket.Purpose.BET, hold).captureRequest();

        assertEquals(hold.from(), capture.from());
        assertEquals(hold.to(), capture.to());
        assertEquals(hold.currency().id(), capture.currencyId());
        assertEquals(hold.category(), capture.category());
        assertEquals(0, hold.amount().compareTo(capture.amount()));
        // Именно equals карты целиком: так же сверяет Core.
        assertEquals(hold.metadata(), capture.metadata());
    }

    @Test
    @DisplayName("Взнос в финал капчурится на СВОЙ счёт, а не в кассу ставок")
    void finalContributionUsesOwnEscrow() {
        UUID operation = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        HoldSnapshot hold = heldAs(operation, player, "colosseum", BetTicket.Purpose.FINAL,
                new BigDecimal("5.00"));

        BetTicket ticket = BetTicket.of(operation, player, "colosseum", BetTicket.Purpose.FINAL, hold);

        assertEquals(hold.to(), ticket.captureRequest().to());
        assertEquals(new AccountId(AccountType.ARENA_ESCROW, "final:colosseum"), ticket.escrow());
        assertNotEquals(new AccountId(AccountType.ARENA_ESCROW, "bet:colosseum"), ticket.escrow());
    }

    @Test
    @DisplayName("Возврат билета отдаёт полное списание, а не саму ставку")
    void ticketRefundReturnsReservedDebit() {
        // Надбавки policy уходят со счёта игрока вместе со ставкой. Вернуть
        // только ставку значило бы оставить налог с отменённой операции себе.
        UUID operation = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        HoldSnapshot hold = new HoldSnapshot(UUID.randomUUID(), "arena-bet:" + operation,
                AccountId.player(player), new AccountId(AccountType.ARENA_ESCROW, "bet:colosseum"),
                CURRENCY, new BigDecimal("10.00"), new BigDecimal("10.50"),
                TransactionCategory.ARENA_BET, "arena-bet", "colosseum",
                HoldSnapshot.Status.HELD, Instant.now(), Instant.now().plusSeconds(120),
                BetTicket.metadata(operation, "colosseum", BetTicket.Purpose.BET));

        BetTicket ticket = BetTicket.of(operation, player, "colosseum", BetTicket.Purpose.BET, hold);

        assertEquals(0, new BigDecimal("10.00").compareTo(ticket.amount()));
        assertEquals(0, new BigDecimal("10.50").compareTo(ticket.reservedDebit()));
    }

    @Test
    @DisplayName("Ключи идемпотентности у разных операций не совпадают")
    void keysAreDistinct() {
        UUID player = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        HoldSnapshot one = heldAs(first, player, "colosseum", BetTicket.Purpose.BET, new BigDecimal("1.00"));
        HoldSnapshot two = heldAs(second, player, "colosseum", BetTicket.Purpose.BET, new BigDecimal("1.00"));

        BetTicket a = BetTicket.of(first, player, "colosseum", BetTicket.Purpose.BET, one);
        BetTicket b = BetTicket.of(second, player, "colosseum", BetTicket.Purpose.BET, two);

        // Два клика по ставке — два разных списания и два разных возврата.
        assertNotEquals(a.refundKey(), b.refundKey());
        assertNotEquals(a.captureRequest().idempotencyKey(), b.captureRequest().idempotencyKey());
        assertTrue(a.refundKey().contains(first.toString()));
    }
}
