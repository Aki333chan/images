package ovh.aurumgg.companion.paper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import ovh.aurumgg.companion.core.model.BalanceChange;
import ovh.aurumgg.companion.core.model.BalanceInfo;
import ovh.aurumgg.companion.core.model.EconomySummary;

/**
 * Экономика через Vault.
 *
 * Vault сам денег не хранит — это прослойка: настоящий плагин экономики
 * (EssentialsX, CMI, любой другой) регистрирует у него провайдера Economy,
 * а мы берём того, кто зарегистрирован. Благодаря этому панель работает с
 * любой экономикой и ничего не знает про конкретный плагин.
 *
 * ПРО ВЫБОР ПЕРЕГРУЗОК. У Economy есть по две версии каждого метода:
 * старые принимают имя игрока строкой и помечены @Deprecated с версии
 * VaultAPI 1.4, новые принимают OfflinePlayer. Здесь используются только
 * новые — сверено по исходникам интерфейса Economy: getBalance(OfflinePlayer),
 * depositPlayer(OfflinePlayer, double), withdrawPlayer(OfflinePlayer, double)
 * аннотации @Deprecated не имеют. Строковые перегрузки, кроме прочего,
 * ломаются при смене ника, а UUID не меняется.
 *
 * Провайдера НЕ кэшируем в поле: плагин экономики может быть перезагружен
 * на живом сервере, и держать ссылку на старый — верный способ получить
 * тихо неработающие начисления.
 */
final class VaultEconomyIntegration {

    private VaultEconomyIntegration() {}

    /** Провайдер экономики или пусто, если Vault нет либо провайдер не зарегистрирован. */
    static Optional<Economy> provider() {
        // Проверка класса нужна на случай, когда Vault не установлен вовсе:
        // обращение к getRegistration(Economy.class) тогда уронило бы поток
        // с NoClassDefFoundError, а не вернуло пусто.
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return Optional.empty();
        try {
            RegisteredServiceProvider<Economy> registration =
                    Bukkit.getServer().getServicesManager().getRegistration(Economy.class);
            if (registration == null) return Optional.empty();
            Economy economy = registration.getProvider();
            // Провайдер может быть зарегистрирован, но выключен — например,
            // если плагин экономики стартовал с ошибкой конфигурации.
            return economy != null && economy.isEnabled() ? Optional.of(economy) : Optional.empty();
        } catch (NoClassDefFoundError | Exception e) {
            return Optional.empty();
        }
    }

    static Optional<BalanceInfo> balance(UUID playerUuid) {
        return provider().map(economy -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
            double value = economy.getBalance(player);
            return new BalanceInfo(value, economy.format(value), economy.currencyNamePlural());
        });
    }

    static Optional<BalanceChange> change(UUID playerUuid, double amount, boolean deposit) {
        return provider().map(economy -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
            double before = economy.getBalance(player);

            EconomyResponse response = deposit
                    ? economy.depositPlayer(player, amount)
                    : economy.withdrawPlayer(player, amount);

            // EconomyResponse.balance — это баланс ПОСЛЕ операции, его и
            // отдаём: пересчитывать getBalance ещё раз значит поймать чужое
            // изменение, случившееся между вызовами.
            boolean ok = response.transactionSuccess();
            double after = ok ? response.balance : before;
            return new BalanceChange(
                    ok,
                    ok ? null : blankToNull(response.errorMessage),
                    before,
                    after,
                    economy.format(after));
        });
    }

    /**
     * Сумма по всем, кто когда-либо заходил, и доска богатства.
     *
     * Bukkit.getOfflinePlayers() — это именно все известные серверу игроки,
     * а не только онлайн: деньги не исчезают, когда человек вышел.
     * Обход не бесплатный (у провайдера это может быть поход в базу),
     * поэтому панель кэширует результат — здесь считаем честно каждый раз.
     */
    static Optional<EconomySummary> summary(int topLimit) {
        return provider().map(economy -> {
            double total = 0;
            int counted = 0;
            List<EconomySummary.TopEntry> entries = new ArrayList<>();

            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                // Игрок без аккаунта в экономике — это ноль, а не ошибка.
                if (!economy.hasAccount(player)) continue;
                double value = economy.getBalance(player);
                total += value;
                counted++;

                String name = player.getName();
                entries.add(new EconomySummary.TopEntry(
                        name == null ? player.getUniqueId().toString() : name,
                        player.getUniqueId().toString(),
                        value,
                        economy.format(value)));
            }

            entries.sort(Comparator.comparingDouble(EconomySummary.TopEntry::balance).reversed());
            List<EconomySummary.TopEntry> top =
                    entries.size() > topLimit ? List.copyOf(entries.subList(0, topLimit)) : List.copyOf(entries);

            return new EconomySummary(total, economy.format(total), economy.currencyNamePlural(), counted, top);
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
