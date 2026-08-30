package ovh.aurumgg.guilds.core;

import java.util.UUID;

/**
 * Деньги игрока — снаружи, через Vault.
 *
 * Банк гильдии не ведёт собственной валюты: это была бы вторая экономика на
 * сервере, где уже есть одна. Он только перекладывает деньги между кошельком
 * игрока и своим балансом, а сам кошелёк живёт в том плагине экономики,
 * который стоит за Vault.
 *
 * Без Vault {@link #available()} возвращает false, команды банка становятся
 * недоступны, а всё остальное в гильдиях работает как обычно.
 */
public interface EconomyBridge {

    /** Есть ли на сервере Vault с зарегистрированным провайдером экономики. */
    boolean available();

    /**
     * Снять с игрока.
     *
     * @return false, если денег не хватило или плагин экономики отказал.
     *         Проверять баланс заранее и полагаться на проверку нельзя: между
     *         проверкой и снятием игрок успевает потратить деньги в другом месте
     */
    boolean withdraw(UUID player, double amount);

    /** Выдать игроку. false — плагин экономики отказал. */
    boolean deposit(UUID player, double amount);

    /** Сумма так, как её принято писать на этом сервере, вместе с валютой. */
    String format(double amount);

    /** Заглушка на случай, когда Vault не установлен. */
    static EconomyBridge unavailable() {
        return new EconomyBridge() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public boolean withdraw(UUID player, double amount) {
                return false;
            }

            @Override
            public boolean deposit(UUID player, double amount) {
                return false;
            }

            @Override
            public String format(double amount) {
                return HudLines.money(amount);
            }
        };
    }
}
