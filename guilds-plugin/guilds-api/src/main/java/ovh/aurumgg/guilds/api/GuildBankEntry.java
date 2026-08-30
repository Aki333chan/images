package ovh.aurumgg.guilds.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Одна операция с банком гильдии.
 *
 * ПИШЕТСЯ ВСЕГДА И НЕ УДАЛЯЕТСЯ. Речь о чужих деньгах, и вопрос «куда делись
 * двадцать тысяч из общака» возникает не в момент операции, а через неделю.
 * Записи переживают и выход участника, и роспуск гильдии — иначе разбирать
 * было бы нечего ровно в том случае, ради которого лог и заводят.
 *
 * @param at           когда
 * @param guildId      какая гильдия
 * @param actorUuid    кто
 * @param actorName    ник на момент операции
 * @param deposit      true — вклад, false — снятие
 * @param amount       сумма, всегда положительная
 * @param balanceAfter баланс банка после операции
 */
public record GuildBankEntry(
        Instant at,
        long guildId,
        UUID actorUuid,
        String actorName,
        boolean deposit,
        double amount,
        double balanceAfter) {}
