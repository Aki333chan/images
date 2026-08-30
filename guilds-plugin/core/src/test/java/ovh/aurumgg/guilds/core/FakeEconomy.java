package ovh.aurumgg.guilds.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Кошельки игроков вместо Vault. */
final class FakeEconomy implements EconomyBridge {

    private final Map<UUID, Double> wallets = new HashMap<>();
    boolean available = true;
    /** Заставить выдачу отказать — так проверяется возврат денег в банк. */
    boolean rejectDeposits;

    void give(UUID player, double amount) {
        wallets.merge(player, amount, Double::sum);
    }

    double balance(UUID player) {
        return wallets.getOrDefault(player, 0.0);
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public boolean withdraw(UUID player, double amount) {
        double have = balance(player);
        if (have < amount) return false;
        wallets.put(player, have - amount);
        return true;
    }

    @Override
    public boolean deposit(UUID player, double amount) {
        if (rejectDeposits) return false;
        give(player, amount);
        return true;
    }

    @Override
    public String format(double amount) {
        return HudLines.money(amount) + " монет";
    }
}
