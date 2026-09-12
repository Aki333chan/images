package dev.addons.npc.service;

import dev.addons.npc.model.GuildBonusType;
import java.time.Duration;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * What one guild-trader purchase owes, in a form that survives a restart.
 *
 * <h2>Steps</h2>
 *
 * <ol>
 *   <li>collect the money;</li>
 *   <li>grant the bonus to the guild.</li>
 * </ol>
 *
 * <h2>What this replaced</h2>
 *
 * The old flow granted the bonus and marked its journal afterwards. A crash in
 * between left a reservation the restarted plugin believed had never been
 * applied — and it recognised the already-granted bonus by a unique
 * {@code guild-actor} string stamped on it. That worked, but only while the
 * bonus was still there: a bonus that expired before recovery ran looked
 * un-granted, the reservation was released, and the guild kept a bonus nobody
 * paid for.
 *
 * A cursor does not depend on the bonus still existing. It says which steps
 * ran, and that answer does not decay.
 *
 * <h2>The actor is still stamped</h2>
 *
 * Not for recovery any more, but because the guild's own bonus log names who
 * granted it, and "an NPC trader, for this player, on this purchase" is what an
 * administrator wants to read there.
 */
public record BonusClaim(String holdKey, String traderId, int slot, long guildId,
                         GuildBonusType type, double magnitude, long durationSeconds, String actor) {

    /** Payment, then the grant. Guild bonus offers carry no console commands. */
    public int stepCount() {
        return 2;
    }

    public boolean paymentStep(int step) {
        return step == 0;
    }

    public boolean grantStep(int step) {
        return step == 1;
    }

    /** Null means permanent, which is what the guilds API expects. */
    public Duration duration() {
        return durationSeconds <= 0 ? null : Duration.ofSeconds(durationSeconds);
    }

    /** Stable per purchase: a repeated capture comes back a duplicate, not a second charge. */
    public String paymentKey() {
        return "npc-guild-claim:" + holdKey;
    }

    public String summary() {
        String bonus = type.name().toLowerCase(java.util.Locale.ROOT) + " x" + magnitude
                + (durationSeconds <= 0 ? " (permanent)" : " for " + durationSeconds + "s");
        return bonus + " to guild " + guildId + " (" + traderId + "#" + slot + ")";
    }

    public String encode() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", 1);
        yaml.set("hold", holdKey);
        yaml.set("trader", traderId);
        yaml.set("slot", slot);
        yaml.set("guild", guildId);
        yaml.set("type", type.name());
        yaml.set("magnitude", magnitude);
        yaml.set("duration", durationSeconds);
        yaml.set("actor", actor);
        return yaml.saveToString();
    }

    /** Empty means the payload cannot be trusted; the caller quarantines rather than guesses. */
    public static Optional<BonusClaim> decode(String payload) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(payload);
            String hold = yaml.getString("hold", "");
            String trader = yaml.getString("trader", "");
            long guild = yaml.getLong("guild", -1L);
            // A bonus type this build no longer knows is exactly the case where
            // guessing would grant the wrong thing.
            GuildBonusType type = GuildBonusType.valueOf(yaml.getString("type", ""));
            if (hold.isBlank() || trader.isBlank() || guild < 0) return Optional.empty();
            return Optional.of(new BonusClaim(hold, trader, yaml.getInt("slot", -1), guild, type,
                    yaml.getDouble("magnitude", 0), yaml.getLong("duration", 0L),
                    yaml.getString("actor", "")));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }
}
