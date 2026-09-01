package ovh.aurumgg.guilds.paper;

import java.util.Optional;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import ovh.aurumgg.guilds.core.EconomyBridge;
import ovh.aurumgg.guilds.core.HudLines;

/**
 * Банк гильдии опирается на ту же экономику, что и весь сервер.
 *
 * Vault сам денег не хранит — это прослойка, за которой стоит настоящий плагин
 * экономики (EssentialsX, CMI, любой другой). Собственную валюту банк гильдии
 * не заводит намеренно: вторая экономика на сервере, где уже есть одна, это
 * гарантированный вопрос «а почему в гильдии деньги другие».
 *
 * <h2>Зависимость мягкая</h2>
 *
 * Классы {@code net.milkbowl.vault.*} упоминаются только здесь, и объект
 * создаётся лишь после {@link #installed()}. На сервере без Vault класс не
 * загружается вовсе, а вместо него работает {@link EconomyBridge#unavailable()}:
 * команды банка отвечают «недоступно», остальное в гильдиях не меняется.
 *
 * <h2>Про перегрузки</h2>
 *
 * Используются только методы, принимающие OfflinePlayer. Строковые перегрузки
 * помечены @Deprecated с VaultAPI 1.4 и ломаются при смене ника — а UUID не
 * меняется.
 */
final class VaultBridge implements EconomyBridge {

    static final String PLUGIN_NAME = "Vault";

    /** Есть ли Vault и зарегистрирован ли за ним живой провайдер экономики. */
    static boolean installed() {
        return provider().isPresent();
    }

    /**
     * Провайдера НЕ держим в поле, и это важнее, чем кажется.
     *
     * Vault сам денег не хранит — это шина. Провайдера за ней регистрирует
     * ТРЕТИЙ плагин (EssentialsX, CMI, любой другой), и происходит это в его
     * собственном onEnable, который вполне может пройти позже нашего: в
     * softdepend его нет и быть не может, мы не знаем, какой именно стоит на
     * сервере.
     *
     * Поэтому вопрос задаётся каждый раз. Тогда провайдер, появившийся через
     * минуту после старта, подхватывается сам, а перезагруженный на живом
     * сервере плагин экономики не оставляет нас со ссылкой на мёртвый
     * экземпляр и тихо неработающими начислениями.
     *
     * Проверка «есть ли вообще Vault» стоит ПЕРВОЙ и намеренно: без неё на
     * сервере без Vault каждое обращение упиралось бы в NoClassDefFoundError,
     * а обращается сюда в том числе задача HUD — несколько раз в секунду на
     * каждого игрока. Поиск плагина по имени — это просмотр карты, он ничего
     * не стоит и не трогает классы Vault.
     */
    private static Optional<Economy> provider() {
        if (Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) == null) return Optional.empty();
        try {
            RegisteredServiceProvider<Economy> registration =
                    Bukkit.getServer().getServicesManager().getRegistration(Economy.class);
            if (registration == null) return Optional.empty();
            Economy economy = registration.getProvider();
            // Провайдер бывает зарегистрирован, но выключен — например, если
            // плагин экономики стартовал с ошибкой конфигурации.
            return economy != null && economy.isEnabled() ? Optional.of(economy) : Optional.empty();
        } catch (NoClassDefFoundError | Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean available() {
        return provider().isPresent();
    }

    @Override
    public boolean withdraw(UUID player, double amount) {
        return provider().map(economy -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(player);
            // Баланс заранее не проверяем: между проверкой и списанием игрок
            // успевает потратить деньги в другом месте, и полагаться на неё
            // было бы способом уйти в минус. Отказ провайдера — единственный
            // надёжный ответ.
            EconomyResponse response = economy.withdrawPlayer(target, amount);
            return response.transactionSuccess();
        }).orElse(false);
    }

    @Override
    public boolean deposit(UUID player, double amount) {
        return provider().map(economy -> economy
                .depositPlayer(Bukkit.getOfflinePlayer(player), amount)
                .transactionSuccess()).orElse(false);
    }

    @Override
    public String format(double amount) {
        // Формат берём у плагина экономики: он знает и название валюты, и то,
        // как её принято писать на этом сервере.
        return provider().map(economy -> economy.format(amount)).orElseGet(() -> HudLines.money(amount));
    }
}
