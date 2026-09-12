package dev.addons.npc.service;

import java.util.List;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * What one shop purchase owes the player, in a form that survives a restart.
 *
 * <h2>Steps, and why they are ordered</h2>
 *
 * A claim remembers progress as a single cursor, so delivery has to be a list
 * of steps that are always attempted in the same order:
 *
 * <ol>
 *   <li>collect the money (only when the purchase was not free);</li>
 *   <li>hand over the item;</li>
 *   <li>each post-purchase command, in the order the shop declares them.</li>
 * </ol>
 *
 * <h2>Why payment is step zero and not a precondition</h2>
 *
 * The record has to exist BEFORE the money moves. If the money moved first, a
 * crash in between would leave a player charged for a purchase nothing knows
 * about — which is the exact failure this class exists to prevent, just moved
 * one step earlier. So the claim is written while the funds are still only
 * reserved, and collecting them is simply the first thing delivery does.
 *
 * <p>The hold is referenced by its idempotency key rather than its id, because
 * the key is what Core looks holds up by — and capturing from the snapshot Core
 * returns is the only way to be sure the capture matches the reservation
 * exactly. Core refuses a capture whose from, to, currency, amount, category or
 * metadata differ by so much as one entry.
 *
 * <h2>Why the item is stored, not looked up</h2>
 *
 * Shop and slot are kept for context, but the item itself is serialized into
 * the payload. An administrator editing shops.yml between the purchase and the
 * delivery is ordinary, and a player who paid yesterday is owed what was on the
 * shelf yesterday — not whatever now sits in that slot.
 *
 * <h2>Placeholders are resolved here</h2>
 *
 * Commands are stored with %player%, %price% and the rest already substituted:
 * they describe what was bought at the price that was paid. Resolving them at
 * delivery time would use tomorrow's balance and today's promise.
 */
public record PurchaseClaim(Optional<String> holdKey, String shopId, int slot, ItemStack item,
                            List<ClaimCommand> commands) {

    public PurchaseClaim {
        commands = List.copyOf(commands);
    }

    /** Payment, the item, then one step per command. */
    public int stepCount() {
        return (holdKey.isPresent() ? 1 : 0) + 1 + commands.size();
    }

    /** Index of the step that hands over the item. */
    public int itemStep() {
        return holdKey.isPresent() ? 1 : 0;
    }

    /** The command a given step runs, or empty when that step is not a command. */
    public Optional<ClaimCommand> commandAt(int step) {
        int first = itemStep() + 1;
        return step >= first && step < first + commands.size()
                ? Optional.of(commands.get(step - first))
                : Optional.empty();
    }

    public boolean paymentStep(int step) {
        return holdKey.isPresent() && step == 0;
    }

    /**
     * One line for {@code /aurum claims}: an administrator looking at a stuck
     * delivery should not have to decode the payload to see what it is.
     */
    public String summary() {
        String item = this.item.getAmount() + "x " + this.item.getType().name().toLowerCase()
                + " (" + shopId + "#" + slot + ")";
        return commands.isEmpty() ? item : item + " + " + commands.size() + " command(s)";
    }

    // ---------------------------------------------------------- кодирование

    /**
     * YAML, not JSON, and deliberately: the plugin already reads and writes YAML
     * everywhere, so this brings no new dependency and no second way to be
     * wrong about encoding. Core never parses the payload either way.
     */
    public String encode() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", 2);
        holdKey.ifPresent(key -> yaml.set("hold", key));
        yaml.set("shop", shopId);
        yaml.set("slot", slot);
        // ItemStack is ConfigurationSerializable, so YamlConfiguration writes
        // and reads it with Bukkit's own format — enchantments, meta and all.
        // Hand-rolling that encoding is how a claim ends up handing back a
        // sword that lost its enchantments.
        yaml.set("item", item);
        yaml.set("commands", commands.stream().map(ClaimCommand::encode).toList());
        return yaml.saveToString();
    }

    /**
     * Read a payload back.
     *
     * <p>Empty means the payload cannot be delivered at all — a truncated write,
     * a downgrade, an item type this server no longer knows. The caller
     * quarantines it rather than guessing, because the alternative is handing a
     * player something other than what they paid for.
     */
    public static Optional<PurchaseClaim> decode(String payload) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(payload);
            ItemStack item = yaml.getItemStack("item");
            if (item == null || item.getAmount() <= 0) return Optional.empty();
            String hold = yaml.getString("hold", "");
            int schema = yaml.getInt("schema", 1);
            return Optional.of(new PurchaseClaim(
                    hold.isBlank() ? Optional.<String>empty() : Optional.of(hold),
                    yaml.getString("shop", ""),
                    yaml.getInt("slot", -1),
                    item,
                    yaml.getStringList("commands").stream()
                            .map(command -> schema >= 2 ? ClaimCommand.stored(command)
                                    : new ClaimCommand(ClaimCommand.Mode.AT_MOST_ONCE, command))
                            .toList()));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }
}
