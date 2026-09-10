package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;
import ovh.aurumgg.core.engine.LedgerEconomyService;

/** Compatibility bridge for synchronous legacy Vault consumers. */
final class AurumVaultEconomy implements Economy {
    private final AurumCorePlugin plugin;
    private final LedgerEconomyService economy;
    private final CurrencySpec currency;
    private final AtomicLong nextSlowWarningAt = new AtomicLong();

    AurumVaultEconomy(AurumCorePlugin plugin, LedgerEconomyService economy, CurrencySpec currency) {
        this.plugin = plugin;
        this.economy = economy;
        this.currency = currency;
    }

    @Override public boolean isEnabled() { return plugin.isEnabled() && plugin.activeReady(); }
    @Override public String getName() { return "AurumCore"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return currency.scale(); }
    @Override public String currencyNamePlural() { return currency.displayName(); }
    @Override public String currencyNameSingular() { return currency.displayName(); }

    @Override
    public String format(double amount) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setGroupingUsed(true);
        format.setMinimumFractionDigits(currency.scale());
        format.setMaximumFractionDigits(currency.scale());
        return format.format(amount) + currency.symbol();
    }

    @Override public boolean hasAccount(String playerName) { return hasAccount(player(playerName)); }
    @Override public boolean hasAccount(OfflinePlayer player) { return player != null; }
    @Override public boolean hasAccount(String playerName, String worldName) { return hasAccount(playerName); }
    @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return hasAccount(player); }

    @Override public double getBalance(String playerName) { return getBalance(player(playerName)); }
    @Override public double getBalance(OfflinePlayer player) {
        if (player == null) return 0.0;
        return economy.cachedBalance(AccountId.player(player.getUniqueId()))
                .map(snapshot -> snapshot.balance().doubleValue()).orElse(0.0);
    }
    @Override public double getBalance(String playerName, String worldName) { return getBalance(playerName); }
    @Override public double getBalance(OfflinePlayer player, String worldName) { return getBalance(player); }

    @Override public boolean has(String playerName, double amount) { return has(player(playerName), amount); }
    @Override public boolean has(OfflinePlayer player, double amount) {
        return validNonNegative(amount) && BigDecimal.valueOf(getBalance(player)).compareTo(BigDecimal.valueOf(amount)) >= 0;
    }
    @Override public boolean has(String playerName, String worldName, double amount) { return has(playerName, amount); }
    @Override public boolean has(OfflinePlayer player, String worldName, double amount) { return has(player, amount); }

    @Override public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(player(playerName), amount);
    }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return mutate(player, amount, false);
    }
    @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(player(playerName), amount);
    }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return mutate(player, amount, true);
    }
    @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    private EconomyResponse mutate(OfflinePlayer player, double rawAmount, boolean deposit) {
        if (player == null) return failure(0, 0, "Player is required");
        BigDecimal amount;
        try {
            if (!Double.isFinite(rawAmount) || rawAmount <= 0) throw new IllegalArgumentException();
            amount = currency.requireAmount(BigDecimal.valueOf(rawAmount));
        } catch (RuntimeException exception) {
            return failure(rawAmount, getBalance(player), "Amount must be positive and use at most "
                    + currency.scale() + " decimal places");
        }
        AccountId account = AccountId.player(player.getUniqueId());
        AccountId system = new AccountId(deposit ? AccountType.SYSTEM_SOURCE : AccountType.SYSTEM_SINK, "global");
        TransactionRequest request = new TransactionRequest(
                "vault:" + (deposit ? "deposit:" : "withdraw:") + UUID.randomUUID(),
                deposit ? system : account,
                deposit ? account : system,
                currency.id(), amount,
                deposit ? TransactionCategory.VAULT_DEPOSIT : TransactionCategory.VAULT_WITHDRAWAL,
                Map.of("bridge", "Vault", "player", player.getUniqueId().toString())
        );
        long started = System.nanoTime();
        try {
            TransactionResult result = economy.transferBlocking(request);
            double balance = getBalance(player);
            EconomyResponse.ResponseType type = result.status() == TransactionResult.Status.SUCCESS
                    || result.status() == TransactionResult.Status.DUPLICATE
                    ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE;
            return new EconomyResponse(amount.doubleValue(), balance, type, result.message());
        } catch (Exception exception) {
            return failure(amount.doubleValue(), getBalance(player), "Ledger unavailable: "
                    + exception.getClass().getSimpleName());
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000L;
            if (millis >= 50) warnAboutSlowWrite(millis);
        }
    }

    private void warnAboutSlowWrite(long millis) {
        long now = System.currentTimeMillis();
        long next = nextSlowWarningAt.get();
        if (now >= next && nextSlowWarningAt.compareAndSet(next, now + 60_000L)) {
            plugin.getLogger().warning("Vault write took " + millis
                    + " ms; further latency warnings are rate-limited for 60 seconds");
        }
    }

    @Override public boolean createPlayerAccount(String playerName) { return createPlayerAccount(player(playerName)); }
    @Override public boolean createPlayerAccount(OfflinePlayer player) {
        if (player == null) return false;
        economy.cacheZeroIfAbsent(AccountId.player(player.getUniqueId()));
        return true;
    }
    @Override public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    @Override public EconomyResponse createBank(String name, String player) { return bankUnsupported(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return bankUnsupported(); }
    @Override public EconomyResponse deleteBank(String name) { return bankUnsupported(); }
    @Override public EconomyResponse bankBalance(String name) { return bankUnsupported(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return bankUnsupported(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return bankUnsupported(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return bankUnsupported(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return bankUnsupported(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return bankUnsupported(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return bankUnsupported(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return bankUnsupported(); }
    @Override public List<String> getBanks() { return List.of(); }

    private EconomyResponse bankUnsupported() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                "AurumCore uses typed accounts instead of Vault banks");
    }

    private EconomyResponse failure(double amount, double balance, String reason) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, reason);
    }

    private OfflinePlayer player(String name) {
        if (name == null || name.isBlank()) return null;
        OfflinePlayer online = plugin.getServer().getPlayerExact(name);
        return online != null ? online : plugin.getServer().getOfflinePlayer(name);
    }

    private static boolean validNonNegative(double amount) {
        return Double.isFinite(amount) && amount >= 0;
    }
}
