package dev.addons.npc.service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.core.api.ClaimRequest;
import ovh.aurumgg.core.api.ClaimResult;
import ovh.aurumgg.core.api.ClaimSnapshot;

/**
 * Hands over what a claim owes, one durable step at a time.
 *
 * <h2>What this replaced</h2>
 *
 * Shop purchases used to give the items first and write the journal afterwards;
 * buyer sales used to take the items first and pay afterwards. A crash in
 * between left a reservation the restarted plugin believed had never been
 * applied — so a purchase released the money and the player kept the goods for
 * free, and a sale released the money after the player's items were already
 * gone. Post-purchase commands were worse still: they ran last and simply
 * vanished with the process.
 *
 * Now the claim is written BEFORE the irreversible step, and Core remembers how
 * many steps are done. A restart resumes at the step that never ran instead of
 * repeating one that did.
 *
 * <h2>Where each step runs</h2>
 *
 * Money is a database operation and answers on Core's executor; items and
 * commands are Minecraft state and must be on the main thread. Every hop back
 * goes through {@link #onMain}, and progress is recorded after each step
 * separately — recording a batch would tell a restarted server that steps ran
 * which never did.
 */
public final class ClaimDelivery implements Listener {

    /**
     * Long enough for a handful of steps, short enough that a crash does not
     * keep a player's goods locked away. Core caps it again on its side, and a
     * step that makes progress renews it.
     */
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final JavaPlugin plugin;
    private final ClaimGateway claims;
    private final MessageService messages;
    /** Claim kind → how to read that kind's payload back into a plan. */
    private final Map<String, Function<String, Optional<DeliveryPlan>>> decoders = new ConcurrentHashMap<>();

    /**
     * Claims this server is already delivering.
     *
     * <p>The lease stops two SERVERS from colliding; this stops one server from
     * colliding with itself, because the join handler and the periodic sweep
     * can reach the same claim within the same tick.
     */
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public ClaimDelivery(JavaPlugin plugin, ClaimGateway claims, MessageService messages) {
        this.plugin = plugin;
        this.claims = claims;
        this.messages = messages;
    }

    /** Teach the loop one kind of claim. Unknown kinds are left alone, not guessed at. */
    public void register(String kind, Function<String, Optional<DeliveryPlan>> decoder) {
        decoders.put(kind, decoder);
    }

    public boolean available() {
        return claims.available();
    }

    public CompletionStage<ClaimResult> promise(ClaimRequest request) {
        return claims.promise(request);
    }

    // ------------------------------------------------------------- triggers

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // A tick later: on join the inventory is restored but not yet settled,
        // and "will this fit" answered too early is answered wrongly.
        Bukkit.getScheduler().runTaskLater(plugin, () -> drain(event.getPlayer()), 20L);
    }

    /** Periodic sweep for everyone online — for claims Core could not serve earlier. */
    public void sweep() {
        if (!claims.available()) return;
        for (Player player : Bukkit.getOnlinePlayers()) drain(player);
    }

    /** Deliver everything this plugin still owes the player. */
    public void drain(Player player) {
        if (!claims.available() || !player.isOnline()) return;
        UUID owner = player.getUniqueId();
        claims.owed(owner).whenComplete((owed, error) -> onMain(() -> {
            if (error != null || owed == null || owed.isEmpty()) return;
            Player online = Bukkit.getPlayer(owner);
            if (online == null) return;
            // Said once for the batch, not once per item: the ordinary case is
            // a purchase made seconds ago, and announcing each step of it would
            // be noise on top of the shop's own confirmation.
            messages.send(online, "delivery-pending", Map.of("count", Integer.toString(owed.size())));
            for (ClaimSnapshot claim : owed) lease(online, claim, null);
        }));
    }

    /**
     * Deliver a claim this server has just written, without re-reading it.
     *
     * <p>Failing here is not a problem: the claim is already durable, so the
     * worst case is the player being served on their next login instead of
     * this second.
     */
    public void deliver(Player player, ClaimSnapshot claim, DeliveryPlan plan) {
        lease(player, claim, plan);
    }

    /**
     * Lease first, read the payload second.
     *
     * <p>The order matters: quarantining a claim requires holding its lease, so
     * a payload that turns out to be unreadable can only be set aside from
     * inside the lease. Reading first would leave an undeliverable claim
     * PENDING for ever, retried by every login and never actually put in front
     * of an administrator.
     */
    private void lease(Player player, ClaimSnapshot claim, DeliveryPlan known) {
        if (!running.add(claim.id())) return;
        claims.take(claim.id(), LEASE).whenComplete((result, error) -> onMain(() -> {
            if (error != null || result == null || !result.ok()) {
                // CONFLICT here is ordinary: someone else got there first.
                running.remove(claim.id());
                return;
            }
            ClaimSnapshot leased = result.claim().orElse(claim);
            DeliveryPlan plan = known != null ? known : decode(leased);
            if (plan == null) {
                // Never guess. Handing over something other than what was paid
                // for is worse than making an administrator look at it.
                plugin.getLogger().warning("NPC claim " + claim.id() + " (" + leased.kind()
                        + ") could not be read and was quarantined; see /aurum claims");
                close(claim.id(), () -> claims.quarantine(claim.id(), "payload could not be read"));
                return;
            }
            step(player.getUniqueId(), claim.id(), plan, leased.stepCursor());
        }));
    }

    private DeliveryPlan decode(ClaimSnapshot claim) {
        Function<String, Optional<DeliveryPlan>> decoder = decoders.get(claim.kind());
        return decoder == null ? null : decoder.apply(claim.payload()).orElse(null);
    }

    // ---------------------------------------------------------------- steps

    private void step(UUID owner, UUID claimId, DeliveryPlan plan, int index) {
        if (index >= plan.stepCount()) {
            close(claimId, () -> claims.settle(claimId));
            return;
        }
        Player player = Bukkit.getPlayer(owner);
        if (player == null || !player.isOnline()) {
            // Not a failure, and deliberately not a counted attempt: leaving
            // mid-delivery a few times would otherwise quarantine a perfectly
            // good claim.
            close(claimId, () -> claims.defer(claimId, "player left before delivery finished"));
            return;
        }
        plan.step(player, index, new StepOutcome(owner, claimId, plan, index));
    }

    /** One step's answer, usable exactly once. */
    private final class StepOutcome implements DeliveryPlan.Outcome {
        private final UUID owner;
        private final UUID claimId;
        private final DeliveryPlan plan;
        private final int index;
        private boolean used;

        private StepOutcome(UUID owner, UUID claimId, DeliveryPlan plan, int index) {
            this.owner = owner;
            this.claimId = claimId;
            this.plan = plan;
            this.index = index;
        }

        @Override
        public void done() {
            if (spent()) return;
            int completed = index + 1;
            claims.advance(claimId, completed).whenComplete((result, error) -> onMain(() -> {
                if (error != null || result == null || !result.ok()) {
                    // The lease is gone, so this server is no longer the one
                    // delivering. Stopping is right: the step just taken is
                    // recorded or it is not, and guessing would repeat it.
                    running.remove(claimId);
                    return;
                }
                step(owner, claimId, plan, completed);
            }));
        }

        @Override
        public void defer(String reason) {
            if (spent()) return;
            close(claimId, () -> claims.defer(claimId, reason));
        }

        @Override
        public void abandon(String reason) {
            if (spent()) return;
            plan.onAbandoned();
            plugin.getLogger().warning("NPC claim " + claimId + " dropped, nothing was owed ("
                    + reason + ")");
            close(claimId, () -> claims.drop(claimId, reason));
        }

        @Override
        public void quarantine(String reason) {
            if (spent()) return;
            plugin.getLogger().warning("NPC claim " + claimId + " quarantined (" + reason
                    + "); see /aurum claims");
            close(claimId, () -> claims.quarantine(claimId, reason));
        }

        private boolean spent() {
            if (used) {
                // A plan that answers twice would advance the cursor past a
                // step that never ran. Loud, because it is a bug in the plan.
                plugin.getLogger().severe("Delivery step " + index + " of claim " + claimId
                        + " answered more than once; the extra answer was ignored");
                return true;
            }
            used = true;
            return false;
        }
    }

    private void close(UUID claimId, java.util.function.Supplier<CompletionStage<ClaimResult>> action) {
        action.get().whenComplete((result, error) -> running.remove(claimId));
    }

    void onMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }
}
