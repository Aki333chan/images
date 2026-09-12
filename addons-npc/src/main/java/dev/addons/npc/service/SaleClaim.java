package dev.addons.npc.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;

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
 * <h2>Why a sale needs no hold</h2>
 *
 * The old flow reserved the payout first, to prove the money existed. It does
 * not need proving: a buyer pays from {@code SYSTEM_SOURCE:npc-buyers}, and a
 * system source is always funded — the ledger says so explicitly. What the
 * reservation actually bought was a promise, and a hold is a poor one because
 * it expires. If the server died between taking the items and capturing, the
 * reservation quietly timed out and the player was left with neither items nor
 * money.
 *
 * A claim is the promise the reservation was standing in for, and it does not
 * expire. So the hold is gone, the payment is an ordinary idempotent transfer,
 * and the order that matters — record first, take items second — is kept by the
 * claim rather than by hope.
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
                        long deadline, List<ClaimCommand> commands) {

    public SaleClaim {
        commands = List.copyOf(commands);
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
        yaml.set("schema", 2);
        yaml.set("operation", operation);
        yaml.set("buyer", buyerId);
        yaml.set("slot", slot);
        yaml.set("amount", amount);
        // As a string: a double would round the payout on the way through YAML,
        // and money that changes when it is written down is not money.
        yaml.set("payout", payout.toPlainString());
        yaml.set("deadline", deadline);
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
            return Optional.of(new SaleClaim(operation, buyer, yaml.getInt("slot", -1), amount, payout,
                    yaml.getLong("deadline", 0L),
                    yaml.getStringList("commands").stream()
                            .map(command -> schema >= 2 ? ClaimCommand.stored(command)
                                    : new ClaimCommand(ClaimCommand.Mode.AT_MOST_ONCE, command))
                            .toList()));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }
}
