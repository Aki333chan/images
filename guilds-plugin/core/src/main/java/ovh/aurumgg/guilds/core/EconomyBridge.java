package ovh.aurumgg.guilds.core;

import java.util.UUID;

/**
 * Деньги гильдии — снаружи: у AurumCore или у плагина экономики за Vault.
 *
 * Собственной валюты банк гильдии не заводит намеренно: вторая экономика на
 * сервере, где уже есть одна, это гарантированный вопрос «а почему в гильдии
 * деньги другие».
 *
 * <h2>Два моста с разной природой, и разницу нельзя прятать</h2>
 *
 * {@code VaultBridge} умеет только кошельки игроков. Счёта гильдии за ним не
 * существует: банк — это число {@code bank_balance} в нашей же таблице, а
 * «вклад» на деле состоит из двух независимых шагов, снять с игрока и
 * прибавить к числу. Между ними нет общей транзакции.
 *
 * {@code AurumCoreBridge} работает со счётом {@code GUILD:<id>} в ledger.
 * Вклад и снятие — это одна атомарная проводка, а {@code bank_balance}
 * становится зеркалом для HUD и панели.
 *
 * Различает их {@link #guildAccounts()}. Спрашивать его обязан всякий, кому
 * важен порядок шагов при отказе: у ledger откатывать нечего, у Vault —
 * есть.
 *
 * <h2>Идемпотентность</h2>
 *
 * Ключи проводок мост придумывает сам. Для вклада и снятия по команде игрока
 * это случайный ключ, и так и должно быть: внести сто монет дважды подряд —
 * законное намерение, а не повтор. Стабильные ключи нужны там, где повтор
 * означал бы двойную выплату: расчёт общака при роспуске и перенос старого
 * баланса.
 *
 * Без экономики {@link #available()} возвращает false, команды банка отвечают
 * «недоступно», а всё остальное в гильдиях работает как обычно.
 */
public interface EconomyBridge {

    /** Есть ли рабочая экономика прямо сейчас. */
    boolean available();

    /**
     * Ведёт ли мост сам счёт гильдии.
     *
     * true — деньги лежат в ledger, проводки атомарны, а возвращаемый баланс
     * авторитетен. false — за мостом только кошельки игроков.
     */
    default boolean guildAccounts() {
        return false;
    }

    /**
     * Кошелёк игрока → банк гильдии.
     *
     * У Vault это только снятие с игрока: прибавить к банку — забота
     * вызывающего.
     */
    BankResult deposit(long guildId, UUID player, double amount);

    /**
     * Банк гильдии → кошелёк игрока.
     *
     * У Vault это только выдача игроку, и вызывающий ОБЯЗАН списать с банка
     * заранее: обратный порядок при отказе выдачи оставил бы деньги и там, и
     * там. У ledger списание и выдача — одна проводка, и списывать заранее
     * нечего.
     */
    BankResult withdraw(long guildId, UUID player, double amount);

    /**
     * Доля из общака распущенной гильдии — конкретному человеку.
     *
     * Отдельно от {@link #withdraw}, потому что ключ обязан быть стабильным:
     * роспуск может оборваться на середине списка получателей, и повтор не
     * должен заплатить дважды.
     */
    BankResult disburse(long guildId, UUID player, double amount, String key);

    /**
     * Общак распущенной гильдии — в казну сервера.
     *
     * Только у ledger: у Vault казны сервера нет, и деньги просто перестают
     * существовать вместе со строкой гильдии — ровно как было до миграции.
     */
    default BankResult toTreasury(long guildId, double amount, String key) {
        return BankResult.success();
    }

    /**
     * Перенести баланс банка, накопленный до миграции, на счёт гильдии.
     *
     * Не «начислить»: эти деньги уже существуют как обязательство сервера
     * перед гильдией и записаны в её журнале. Проводка только переносит их
     * туда, где у них появляется владелец.
     */
    default BankResult seed(long guildId, double amount) {
        return BankResult.success();
    }

    /** Сумма так, как её принято писать на этом сервере, вместе с валютой. */
    String format(double amount);

    /** Заглушка на случай, когда экономики нет. */
    static EconomyBridge unavailable() {
        return new EconomyBridge() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public BankResult deposit(long guildId, UUID player, double amount) {
                return BankResult.unavailable();
            }

            @Override
            public BankResult withdraw(long guildId, UUID player, double amount) {
                return BankResult.unavailable();
            }

            @Override
            public BankResult disburse(long guildId, UUID player, double amount, String key) {
                return BankResult.unavailable();
            }

            @Override
            public String format(double amount) {
                return HudLines.money(amount);
            }
        };
    }
}
