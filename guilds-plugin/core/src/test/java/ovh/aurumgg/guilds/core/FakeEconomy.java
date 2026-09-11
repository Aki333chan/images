package ovh.aurumgg.guilds.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Кошельки игроков и, по желанию, счета гильдий — вместо настоящей экономики.
 *
 * Один класс на оба режима намеренно: разница между Vault и ledger в том,
 * ГДЕ лежат деньги гильдии, и проверять поведение сервиса надо в обоих
 * режимах на одних и тех же сценариях. Переключает {@link #guildAccounts}.
 */
final class FakeEconomy implements EconomyBridge {

    private final Map<UUID, Double> wallets = new HashMap<>();
    /** Счета гильдий — только в режиме ledger. */
    private final Map<Long, Double> vaults = new HashMap<>();
    /** Использованные ключи проводок: так проверяется идемпотентность. */
    final Set<String> keys = new HashSet<>();
    boolean available = true;
    /** Заставить выдачу отказать — так проверяется возврат денег в банк. */
    boolean rejectDeposits;
    /** Ledger-режим: у гильдии есть собственный счёт. */
    boolean guildAccounts;
    /** Сколько ушло в казну сервера за все роспуски. */
    double treasury;

    void give(UUID player, double amount) {
        wallets.merge(player, amount, Double::sum);
    }

    double balance(UUID player) {
        return wallets.getOrDefault(player, 0.0);
    }

    double vault(long guildId) {
        return vaults.getOrDefault(guildId, 0.0);
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public boolean guildAccounts() {
        return guildAccounts;
    }

    @Override
    public BankResult deposit(long guildId, UUID player, double amount) {
        double have = balance(player);
        if (have < amount) return BankResult.notEnough();
        wallets.put(player, have - amount);
        if (!guildAccounts) return BankResult.success();
        double balance = vault(guildId) + amount;
        vaults.put(guildId, balance);
        return BankResult.success(balance);
    }

    @Override
    public BankResult withdraw(long guildId, UUID player, double amount) {
        if (rejectDeposits) return BankResult.refused();
        if (guildAccounts) {
            double have = vault(guildId);
            if (have < amount) return BankResult.notEnough();
            vaults.put(guildId, have - amount);
            give(player, amount);
            return BankResult.success(have - amount);
        }
        give(player, amount);
        return BankResult.success();
    }

    @Override
    public BankResult disburse(long guildId, UUID player, double amount, String key) {
        // Повтор с тем же ключом не платит второй раз — как настоящий ledger.
        if (!keys.add(key)) return BankResult.success();
        return withdraw(guildId, player, amount);
    }

    @Override
    public BankResult toTreasury(long guildId, double amount, String key) {
        if (!keys.add(key)) return BankResult.success();
        if (guildAccounts) {
            double have = vault(guildId);
            if (have < amount) return BankResult.notEnough();
            vaults.put(guildId, have - amount);
        }
        treasury += amount;
        return BankResult.success();
    }

    @Override
    public BankResult seed(long guildId, double amount) {
        if (!keys.add("guild-bank-migration:" + guildId)) return BankResult.success();
        vaults.merge(guildId, amount, Double::sum);
        return BankResult.success(vault(guildId));
    }

    @Override
    public String format(double amount) {
        return HudLines.money(amount) + " монет";
    }
}
