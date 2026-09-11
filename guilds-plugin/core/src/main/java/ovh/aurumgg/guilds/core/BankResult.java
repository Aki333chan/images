package ovh.aurumgg.guilds.core;

import java.util.OptionalDouble;

/**
 * Чем закончилось движение денег между кошельком игрока и банком гильдии.
 *
 * <h2>Почему не boolean</h2>
 *
 * Раньше мост отвечал true/false, а причину отказа выбирал вызывающий по
 * тому, какой метод он вызвал: не прошло снятие с игрока — значит «не хватило
 * денег», не прошла выдача — значит «плагин экономики отказал». С ledger это
 * перестало быть правдой: одна и та же проводка отклоняется и из-за нехватки
 * средств, и из-за недоступности Core, и знает об этом только мост.
 *
 * <h2>Почему баланс необязателен</h2>
 *
 * Когда деньги гильдии лежат на счёте {@code GUILD:<id>}, авторитетный баланс
 * после проводки возвращает сам ledger, и записывать в базу гильдий надо
 * именно его. Когда за мостом Vault, счёта гильдии не существует вовсе:
 * баланс — это число в нашей же базе, и считает его вызывающий. Пустой
 * {@link OptionalDouble} — это честное «мост не знает», а не ноль.
 *
 * @param ok         прошло ли движение
 * @param messageKey ключ причины отказа; у успеха пустая строка
 * @param balance    баланс банка после проводки, если мост его ведёт
 */
public record BankResult(boolean ok, String messageKey, OptionalDouble balance) {

    /**
     * Успех без известного банку баланса: считать его вызывающему.
     *
     * Называется success, а не ok: у record с компонентом {@code ok} метод
     * {@code ok()} уже занят его же геттером.
     */
    public static BankResult success() {
        return new BankResult(true, "", OptionalDouble.empty());
    }

    /** Успех с авторитетным балансом счёта гильдии после проводки. */
    public static BankResult success(double balance) {
        return new BankResult(true, "", OptionalDouble.of(balance));
    }

    public static BankResult fail(String messageKey) {
        return new BankResult(false, messageKey, OptionalDouble.empty());
    }

    /** Денег не хватило — у плательщика, кем бы он ни был. */
    public static BankResult notEnough() {
        return fail("guild.err.notEnoughMoney");
    }

    /** Экономика на месте, но провести отказалась. */
    public static BankResult refused() {
        return fail("guild.err.economyRefused");
    }

    /** Экономики нет вовсе или она ещё не готова. */
    public static BankResult unavailable() {
        return fail("guild.err.bankOff");
    }
}
