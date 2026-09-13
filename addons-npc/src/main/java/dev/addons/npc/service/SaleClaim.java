package dev.addons.npc.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;

/**
 * What one buyer sale owes, in a form that survives a restart.
 *
 * <h2>Steps</h2>
 *
 * <ol>
 *   <li>take the items;</li>
 *   <li>pay for them;</li>
 *   <li>each post-sale command, in the order the offer declares them.</li>
 * </ol>
 *
 * <h2>Finite buyer budgets</h2>
 *
 * New claims reserve the quoted payout from the configured buyer/treasury
 * account before this claim is promised. The claim keeps the hold key and the
 * exact source. Items are still taken only after the durable claim exists; the
 * next step captures the reservation. If a long outage lets the hold expire,
 * the claim remains a visible debt and retries the same idempotent payment from
 * the stored source instead of forgetting what the player is owed.
 *
 * Schema 1/2 claims have no source/hold fields and retain their historical
 * {@code SYSTEM_SOURCE:npc-buyers} settlement so an upgrade cannot strand an
 * already recorded sale.
 *
 * <h2>Why the sale still has a deadline</h2>
 *
 * Not to protect the money, but the player. A sale clicked before a crash and
 * resumed a week later would take items the player has long since decided to
 * keep. Past the deadline the claim is dropped: nothing was taken and nothing
 * was paid, so there is nothing to settle.
 *
 * @param operation stable id of this sale; the payment key is built from it, so
 *                  a repeated payment comes back a duplicate, not a second one
 * @param payout    what was quoted, kept exactly — the offer's price may change
 *                  between the click and the delivery, but the quote may not
 */
public record SaleClaim(String operation, String buyerId, int slot, int amount, BigDecimal payout,
                        long deadline, String budgetSource, String holdKey,
                        List<ClaimCommand> commands) {

    public SaleClaim {
        budgetSource = budgetSource == null ? "" : budgetSource.trim().toLowerCase(java.util.Locale.ROOT);
        holdKey = holdKey == null ? "" : holdKey.trim();
        if (budgetSource.isBlank() != holdKey.isBlank()) {
            throw new IllegalArgumentException("Budget source and hold key must both be present or absent");
        }
        if (!budgetSource.isBlank()) parseAccount(budgetSource);
        commands = List.copyOf(commands);
    }

    /** Compatibility constructor for claims written before finite buyer budgets. */
    public SaleClaim(String operation, String buyerId, int slot, int amount, BigDecimal payout,
                     long deadline, List<ClaimCommand> commands) {
        this(operation, buyerId, slot, amount, payout, deadline, "", "", commands);
    }

    public boolean reservedBudget() { return !holdKey.isBlank(); }

    public AccountId budgetAccount() {
        if (budgetSource.isBlank()) return new AccountId(AccountType.SYSTEM_SOURCE, "npc-buyers");
        return parseAccount(budgetSource);
    }

    private static AccountId parseAccount(String stableKey) {
        int separator = stableKey.indexOf(':');
        if (separator < 1 || separator == stableKey.length() - 1) {
            throw new IllegalArgumentException("Invalid buyer budget account");
        }
        return new AccountId(AccountType.valueOf(stableKey.substring(0, separator)
                .toUpperCase(java.util.Locale.ROOT)), stableKey.substring(separator + 1));
    }

    /** Items, payment, then one step per command. */
    public int stepCount() {
        return 2 + commands.size();
    }

    public boolean itemStep(int step) {
        return step == 0;
    }

    public boolean paymentStep(int step) {
        return step == 1;
    }

    public Optional<ClaimCommand> commandAt(int step) {
        return step >= 2 && step < 2 + commands.size()
                ? Optional.of(commands.get(step - 2))
                : Optional.empty();
    }

    /** Idempotency key of the payment. Stable for the life of the sale. */
    public String paymentKey() {
        return "npc-sale:" + operation;
    }

    public String summary() {
        String sale = amount + " item(s) to " + buyerId + "#" + slot + " for "
                + payout.stripTrailingZeros().toPlainString();
        return commands.isEmpty() ? sale : sale + " + " + commands.size() + " command(s)";
    }

    public String encode() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", 3);
        yaml.set("operation", operation);
        yaml.set("buyer", buyerId);
        yaml.set("slot", slot);
        yaml.set("amount", amount);
        // As a string: a double would round the payout on the way through YAML,
        // and money that changes when it is written down is not money.
        yaml.set("payout", payout.toPlainString());
        yaml.set("deadline", deadline);
        yaml.set("budget-source", budgetSource.isBlank() ? null : budgetSource);
        yaml.set("hold-key", holdKey.isBlank() ? null : holdKey);
        yaml.set("commands", commands.stream().map(ClaimCommand::encode).toList());
        return yaml.saveToString();
    }

    /** Empty means the payload cannot be trusted; the caller quarantines rather than guesses. */
    public static Optional<SaleClaim> decode(String payload) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(payload);
            String operation = yaml.getString("operation", "");
            String buyer = yaml.getString("buyer", "");
            int amount = yaml.getInt("amount", 0);
            BigDecimal payout = new BigDecimal(yaml.getString("payout", ""));
            if (operation.isBlank() || buyer.isBlank() || amount <= 0 || payout.signum() <= 0) {
                return Optional.empty();
            }
            int schema = yaml.getInt("schema", 1);
            String budgetSource = schema >= 3 ? yaml.getString("budget-source", "") : "";
            String holdKey = schema >= 3 ? yaml.getString("hold-key", "") : "";
            return Optional.of(new SaleClaim(operation, buyer, yaml.getInt("slot", -1), amount, payout,
                    yaml.getLong("deadline", 0L), budgetSource, holdKey,
                    yaml.getStringList("commands").stream()
                            .map(command -> schema >= 2 ? ClaimCommand.stored(command)
                                    : new ClaimCommand(ClaimCommand.Mode.AT_MOST_ONCE, command))
                            .toList()));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }
}
