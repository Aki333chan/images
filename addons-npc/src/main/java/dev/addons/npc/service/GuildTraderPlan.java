package dev.addons.npc.service;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.HoldSnapshot;

/**
 * Delivery of one guild-trader purchase: collect the money, then grant the bonus.
 *
 * <p>Money first is deliberate. If the grant came first, a crash before the
 * capture would hand a guild a bonus nobody paid for — which is exactly the
 * hole the old flow had, and the reason it needed to recognise already-granted
 * bonuses by a stamped actor string. Paying first turns that into an ordinary
 * owed delivery: the guild is due a bonus, and the cursor says so without
 * having to look at the guild's bonus list at all.
 */
public final class GuildTraderPlan implements DeliveryPlan {

    public static final String KIND = "GUILD_BONUS";

    private final JavaPlugin plugin;
    private final EconomyService economy;
    private final MessageService messages;
    private final AurumGuildsHook guilds;
    private final ClaimDelivery delivery;
    private final BonusClaim bonus;

    public GuildTraderPlan(JavaPlugin plugin, EconomyService economy, MessageService messages,
                           AurumGuildsHook guilds, ClaimDelivery delivery, BonusClaim bonus) {
        this.plugin = plugin;
        this.economy = economy;
        this.messages = messages;
        this.guilds = guilds;
        this.delivery = delivery;
        this.bonus = bonus;
    }

    @Override public String kind() { return KIND; }
    @Override public int stepCount() { return bonus.stepCount(); }
    @Override public String summary() { return bonus.summary(); }
    @Override public String encode() { return bonus.encode(); }

    @Override
    public void step(Player player, int index, Outcome outcome) {
        if (bonus.paymentStep(index)) {
            collect(outcome);
            return;
        }
        if (bonus.grantStep(index)) {
            grant(player, outcome);
            return;
        }
        outcome.quarantine("step " + index + " is not part of this purchase");
    }

    /**
     * Take the money that was reserved when the player clicked.
     *
     * <p>Captured from the snapshot Core returns, never from values kept here:
     * Core refuses a capture that differs from the reservation by even one
     * metadata entry.
     */
    private void collect(Outcome outcome) {
        economy.hold(bonus.holdKey()).whenComplete((found, error) -> delivery.onMain(() -> {
            if (error != null) {
                outcome.defer("AurumCore did not answer");
                return;
            }
            HoldSnapshot hold = found == null ? null : found.orElse(null);
            if (hold == null || hold.status() == HoldSnapshot.Status.RELEASED
                    || hold.status() == HoldSnapshot.Status.EXPIRED
                    || hold.status() == HoldSnapshot.Status.REJECTED) {
                // Nothing was charged, so no bonus is owed.
                outcome.abandon(hold == null ? "reservation is gone"
                        : "reservation " + hold.status().name().toLowerCase(java.util.Locale.ROOT));
                return;
            }
            if (hold.status() == HoldSnapshot.Status.CAPTURED) {
                outcome.done();
                return;
            }
            economy.capture(hold, bonus.paymentKey()).whenComplete((result, failure) ->
                    delivery.onMain(() -> {
                if (failure == null && result != null && (result.status() == HoldResult.Status.SUCCESS
                        || result.status() == HoldResult.Status.DUPLICATE)) {
                    outcome.done();
                } else if (result != null && result.status() == HoldResult.Status.REJECTED) {
                    outcome.abandon("capture rejected: " + result.message());
                } else {
                    outcome.defer("AurumCore is unavailable");
                }
            }));
        }));
    }

    /**
     * Grant the bonus the guild has already paid for.
     *
     * <p>The money is gone by now, so nothing here gives up quietly: without
     * AurumGuilds, or with the guild plugin refusing, the claim waits rather
     * than disappearing.
     */
    private void grant(Player player, Outcome outcome) {
        if (!guilds.available()) {
            outcome.defer("AurumGuilds is unavailable");
            return;
        }
        guilds.grant(bonus.guildId(), bonus.type(), bonus.magnitude(), bonus.duration(), bonus.actor())
                .whenComplete((result, error) -> delivery.onMain(() -> {
            if (error == null && result != null && result.ok()) {
                messages.send(player, "guild-purchase-success", java.util.Map.of("result", result.message()));
                outcome.done();
                return;
            }
            String reason = result == null ? "AurumGuilds did not answer" : result.message();
            // A guild that was disbanded between the payment and the grant is
            // not something a retry fixes, and the money has already moved:
            // that is precisely what quarantine is for.
            plugin.getLogger().warning("Guild bonus could not be granted (" + reason + ")");
            outcome.quarantine("bonus not granted: " + reason);
        }));
    }
}
