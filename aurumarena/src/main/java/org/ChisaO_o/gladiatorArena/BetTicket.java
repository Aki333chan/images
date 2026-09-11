package org.ChisaO_o.gladiatorArena;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.HoldSnapshot;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;

/**
 * Одно денежное списание с игрока, ещё не доведённое до конца.
 *
 * <h2>Зачем запись, если есть recovery.yml</h2>
 *
 * В {@code recovery.yml} лежит НАКОПЛЕННАЯ ставка игрока — то, что ему вернут
 * при перезапуске. Но между «деньги ушли со счёта» и «ставка записана в
 * recovery.yml» есть окно, и падение ровно в нём оставило бы деньги в эскроу,
 * о которых не помнит никто. Билет закрывает именно это окно и живёт ровно
 * столько, сколько оно открыто.
 *
 * <h2>Почему один клик — один билет</h2>
 *
 * Ставку делают по шагу: три клика — три списания. В recovery.yml они
 * складываются в одну сумму, а в Core это три разных hold с разными ключами.
 * Возврат накопленной суммы и снятие одного незавершённого списания — разные
 * деньги, поэтому и ключи идемпотентности у них разные.
 *
 * @param operation  собственный идентификатор попытки; из него строятся ключи
 * @param playerId   кого списали
 * @param arena      арена, за чью кассу идёт речь
 * @param purpose    ставка на бой или взнос в финальную кассу
 * @param holdId     идентификатор резерва в Core
 * @param holdKey    ключ идемпотентности резерва — по нему его находят заново
 * @param amount     сама ставка — столько зачисляется на счёт арены
 * @param reservedDebit сколько реально списано с игрока: ставка плюс надбавки
 *                   policy engine. Возвращать надо именно это, иначе налог с
 *                   отменённой ставки останется у сервера
 * @param currencyId валюта Core
 */
record BetTicket(UUID operation, UUID playerId, String arena, Purpose purpose,
                 UUID holdId, String holdKey, BigDecimal amount, BigDecimal reservedDebit,
                 String currencyId) {

    /** На что ушли деньги: от этого зависит счёт-получатель. */
    enum Purpose { BET, FINAL }

    static BetTicket of(UUID operation, UUID playerId, String arena, Purpose purpose,
                        HoldSnapshot hold) {
        return new BetTicket(operation, playerId, arena, purpose, hold.id(), hold.idempotencyKey(),
                hold.amount(), hold.reservedAmount(), hold.currency().id());
    }

    /** Счёт, на котором лежат деньги этой арены. Касса боя и финал — разные. */
    AccountId escrow() {
        return new AccountId(AccountType.ARENA_ESCROW, purpose.name().toLowerCase(java.util.Locale.ROOT) + ":" + arena);
    }

    /**
     * Проводка, которой резерв превращается в настоящее списание.
     *
     * Сумма — {@code amount}, а не {@code reservedDebit}: надбавки policy
     * engine Core разносит сам, и подставить сюда полное списание значило бы
     * посчитать их дважды.
     */
    TransactionRequest captureRequest() {
        return new TransactionRequest("arena-capture:" + operation, AccountId.player(playerId),
                escrow(), currencyId, amount, TransactionCategory.ARENA_BET, metadata());
    }

    /** Ключ возврата именно ЭТОГО списания — не путать с возвратом накопленной ставки. */
    String refundKey() {
        return "arena-ticket-refund:" + operation;
    }

    Map<String, String> metadata() {
        return Map.of("plugin", "AurumArena", "arena", arena,
                "purpose", purpose.name().toLowerCase(java.util.Locale.ROOT),
                "operation", operation.toString());
    }
}
