package dev.addons.npc.service;

import java.util.Optional;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class EconomyService {
    private final JavaPlugin plugin;
    private Economy economy;

    public EconomyService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean hook() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            economy = null;
            return false;
        }
        RegisteredServiceProvider<Economy> registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        economy = registration == null ? null : registration.getProvider();
        return economy != null;
    }

    public boolean available() { return economy != null; }
    public double balance(OfflinePlayer player) { return economy == null ? 0 : economy.getBalance(player); }
    public String format(double amount) { return economy == null ? String.format("%.2f", amount) : economy.format(amount); }

    public Optional<String> withdraw(OfflinePlayer player, double amount) {
        if (economy == null) {
            return Optional.of("Economy provider is unavailable");
        }
        try {
            EconomyResponse response = economy.withdrawPlayer(player, amount);
            return response.transactionSuccess() ? Optional.empty() : failure(response, "Vault rejected the withdrawal");
        } catch (RuntimeException exception) {
            return Optional.of("Vault provider threw " + exception.getClass().getSimpleName());
        }
    }

    public Optional<String> deposit(OfflinePlayer player, double amount) {
        if (economy == null) return Optional.of("Economy provider is unavailable");
        if (!Double.isFinite(amount) || amount <= 0) return Optional.of("Deposit amount must be positive");
        try {
            EconomyResponse response = economy.depositPlayer(player, amount);
            return response.transactionSuccess() ? Optional.empty() : failure(response, "Vault rejected the deposit");
        } catch (RuntimeException exception) {
            return Optional.of("Vault provider threw " + exception.getClass().getSimpleName());
        }
    }

    public void refund(OfflinePlayer player, double amount) {
        if (economy != null && amount > 0) {
            economy.depositPlayer(player, amount);
        }
    }

    private static Optional<String> failure(EconomyResponse response, String fallback) {
        String error = response.errorMessage;
        return Optional.of(error == null || error.isBlank() ? fallback : error);
    }
}
