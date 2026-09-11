package dev.addons.npc.service;

import dev.addons.npc.config.ShopRepository;
import dev.addons.npc.model.ShopDefinition;
import dev.addons.npc.model.ShopOffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.core.api.ClaimResult;
import ovh.aurumgg.core.api.ClaimSnapshot;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.HoldSnapshot;

/**
 * Hands over what a shop purchase owes, one durable step at a time.
 *
 * <h2>What this replaced</h2>
 *
 * Purchases used to give the items first and write the journal afterwards. A
 * crash in between left a reservation the restarted plugin believed was never
 * applied: it released the money, and the player kept the goods for free.
 * Post-purchase commands were worse — they ran last and simply vanished with
 * the process.
 *
 * Now the claim is written while the money is still only reserved, and delivery
 * is a list of ordered steps: collect the money, hand over the item, run each
 * command. The cursor Core keeps says which of them already happened, so a
 * restart resumes at the step that never ran instead of repeating one that did.
 *
 * <h2>Where each step runs</h2>
 *
 * Capture is a database operation and answers on Core's executor. Items and
 * commands are Minecraft state and must be on the main thread. Every hop back
 * goes through {@link #onMain}, and progress is recorded after each step
 * separately — recording a batch would tell a restarted server that steps ran
 * which never did.
 */
public final class ShopDelivery implements Listener {

    /**
     * Long enough for a handful of steps, short enough that a crash does not
     * keep a player's goods locked away. Core caps it again on its side.
     */
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final JavaPlugin plugin;
    private final ClaimGateway claims;
    private final EconomyService economy;
    private final MessageService messages;
    private final ShopRepository shops;

    /**
     * Claims this server is already delivering.
     *
     * <p>The lease stops two SERVERS from colliding; this stops one server from
     * colliding with itself, because the join handler and the periodic sweep
     * can reach the same claim within the same tick.
     */
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public ShopDelivery(JavaPlugin plugin, ClaimGateway claims, EconomyService economy,
                        MessageService messages, ShopRepository shops) {
        this.plugin = plugin;
        this.claims = claims;
        this.economy = economy;
        this.messages = messages;
        this.shops = shops;
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
            for (ClaimSnapshot claim : owed) begin(online, claim);
        }));
    }

    public boolean available() {
        return claims.available();
    }

    public java.util.concurrent.CompletionStage<ClaimResult> promise(
            ovh.aurumgg.core.api.ClaimRequest request) {
        return claims.promise(request);
    }

    /**
     * Deliver a claim this server has just written, without re-reading it.
     *
     * <p>Failing here is not a problem: the claim is already durable, so the
     * worst case is the player being served on their next login instead of
     * this second.
     */
    public void deliver(Player player, ClaimSnapshot claim, PurchaseClaim purchase) {
        lease(player, claim, purchase);
    }

    /**
     * Lease first, read the payload second.
     *
     * <p>The order matters: quarantining a claim requires holding its lease,
     * so a payload that turns out to be unreadable can only be set aside from
     * inside the lease. Reading first would leave an undeliverable claim
     * PENDING for ever, retried by every login and never actually put in front
     * of an administrator.
     */
    private void begin(Player player, ClaimSnapshot claim) {
        lease(player, claim, null);
    }

    private void lease(Player player, ClaimSnapshot claim, PurchaseClaim known) {
        if (!running.add(claim.id())) return;
        claims.take(claim.id(), LEASE).whenComplete((result, error) -> onMain(() -> {
            if (error != null || result == null || !result.ok()) {
                // CONFLICT here is ordinary: someone else got there first.
                running.remove(claim.id());
                return;
            }
            ClaimSnapshot leased = result.claim().orElse(claim);
            PurchaseClaim parsed = known != null
                    ? known
                    : PurchaseClaim.decode(leased.payload()).orElse(null);
            if (parsed == null) {
                // Never guess. Handing over something other than what was paid
                // for is worse than making an administrator look at it.
                plugin.getLogger().warning("NPC claim " + claim.id() + " has an unreadable payload "
                        + "and was quarantined; see /aurum claims");
                finish(claim.id(), () -> claims.quarantine(claim.id(), "payload could not be read"));
                return;
            }
            step(player.getUniqueId(), claim.id(), parsed, leased.stepCursor());
        }));
    }

    // ---------------------------------------------------------------- steps

    private void step(UUID owner, UUID claimId, PurchaseClaim purchase, int index) {
        if (index >= purchase.stepCount()) {
            finish(claimId, () -> claims.settle(claimId));
            return;
        }
        if (purchase.paymentStep(index)) {
            collect(owner, claimId, purchase, index);
            return;
        }
        Player player = Bukkit.getPlayer(owner);
        if (player == null || !player.isOnline()) {
            // Not a failure: the rest waits for the next login. It does not
            // count as a failed attempt either, or leaving mid-delivery a few
            // times would quarantine a perfectly good claim.
            finish(claimId, () -> claims.defer(claimId, "player left before delivery finished"));
            return;
        }
        Optional<String> command = purchase.commandAt(index);
        if (command.isPresent()) {
            runCommand(command.get(), claimId);
            advance(owner, claimId, purchase, index);
            return;
        }
        giveItem(player, owner, claimId, purchase, index);
    }

    private void giveItem(Player player, UUID owner, UUID claimId, PurchaseClaim purchase, int index) {
        ItemStack reward = purchase.item().clone();
        if (!ShopService.canFit(player.getInventory().getStorageContents(), reward)) {
            // Deferring counts an attempt, and that is correct: a player who
            // never frees a slot should end up in quarantine rather than have
            // this retried on every login for ever.
            messages.send(player, "delivery-inventory-full");
            finish(claimId, () -> claims.defer(claimId, "inventory full"));
            return;
        }
        HashMap<Integer, ItemStack> leftovers = new HashMap<>(player.getInventory().addItem(reward));
        if (!leftovers.isEmpty()) {
            // The inventory changed between the check and the insert. Put back
            // what did go in, so the retry starts from a clean state.
            leftovers.values().forEach(left -> reward.setAmount(reward.getAmount() - left.getAmount()));
            if (reward.getAmount() > 0) player.getInventory().removeItem(reward);
            finish(claimId, () -> claims.defer(claimId, "inventory changed during delivery"));
            return;
        }
        player.updateInventory();
        advance(owner, claimId, purchase, index);
    }

    private void runCommand(String command, UUID claimId) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (RuntimeException failure) {
            // The command ran and threw. Retrying an arbitrary console command
            // is not safe, so the step counts as done and the failure is loud
            // in the log instead.
            plugin.getLogger().warning("Shop command for claim " + claimId + " failed: "
                    + failure.getMessage());
        }
    }

    /**
     * Step zero: take the money that was reserved when the player clicked.
     *
     * <p>Captured from the snapshot Core returns, never from values kept here:
     * Core refuses a capture that differs from the reservation by even one
     * metadata entry, and refusing after the money is reserved is the worst
     * moment to find that out.
     */
    private void collect(UUID owner, UUID claimId, PurchaseClaim purchase, int index) {
        String key = purchase.holdKey().orElse("");
        economy.hold(key).whenComplete((found, error) -> onMain(() -> {
            if (error != null) {
                finish(claimId, () -> claims.defer(claimId, "AurumCore did not answer"));
                return;
            }
            HoldSnapshot hold = found == null ? null : found.orElse(null);
            if (hold == null || hold.status() == HoldSnapshot.Status.RELEASED
                    || hold.status() == HoldSnapshot.Status.EXPIRED
                    || hold.status() == HoldSnapshot.Status.REJECTED) {
                // Nothing was ever charged, so nothing is owed. Dropping is the
                // honest answer; quarantining would put a debt that does not
                // exist in front of an administrator.
                abandon(claimId, purchase, hold == null ? "reservation is gone" : "reservation "
                        + hold.status().name().toLowerCase(java.util.Locale.ROOT));
                return;
            }
            if (hold.status() == HoldSnapshot.Status.CAPTURED) {
                advance(owner, claimId, purchase, index);
                return;
            }
            economy.capture(hold, "npc-claim:" + claimId).whenComplete((result, failure) -> onMain(() -> {
                if (failure == null && result != null && (result.status() == HoldResult.Status.SUCCESS
                        || result.status() == HoldResult.Status.DUPLICATE)) {
                    advance(owner, claimId, purchase, index);
                } else if (result != null && result.status() == HoldResult.Status.REJECTED) {
                    // The reservation and the capture no longer describe the
                    // same operation. Money did not move; there is nothing to
                    // hand over and nothing for an administrator to settle.
                    abandon(claimId, purchase, "capture rejected: " + result.message());
                } else {
                    finish(claimId, () -> claims.defer(claimId, "AurumCore is unavailable"));
                }
            }));
        }));
    }

    /**
     * The purchase never happened: put the stock back and close the claim.
     *
     * <p>Stock was taken when the claim was written, because at that moment the
     * player was about to be charged. When the charge turns out never to have
     * happened, the shelf has to be put back the way it was.
     */
    private void abandon(UUID claimId, PurchaseClaim purchase, String reason) {
        restock(purchase);
        plugin.getLogger().warning("NPC claim " + claimId + " dropped without charging anyone ("
                + reason + ")");
        finish(claimId, () -> claims.drop(claimId, reason));
    }

    private void restock(PurchaseClaim purchase) {
        try {
            ShopDefinition shop = shops.get(purchase.shopId());
            ShopOffer offer = shop == null ? null : shop.offers().get(purchase.slot());
            if (offer == null || offer.unlimited()) return;
            offer.stock(offer.stock() + 1);
            shops.save();
        } catch (RuntimeException failure) {
            // One unit of stock is not money. Losing it is a cosmetic drift
            // worth a log line, not a reason to leave the claim open.
            plugin.getLogger().log(Level.WARNING, "Could not restore shop stock", failure);
        }
    }

    private void advance(UUID owner, UUID claimId, PurchaseClaim purchase, int index) {
        int done = index + 1;
        claims.advance(claimId, done).whenComplete((result, error) -> onMain(() -> {
            if (error != null || result == null || !result.ok()) {
                // The lease is gone, so this server is no longer the one
                // delivering. Stopping is right: the step just taken is
                // recorded or it is not, and guessing would repeat it.
                running.remove(claimId);
                return;
            }
            step(owner, claimId, purchase, done);
        }));
    }

    private void finish(UUID claimId, java.util.function.Supplier<java.util.concurrent.CompletionStage<ClaimResult>> action) {
        action.get().whenComplete((result, error) -> running.remove(claimId));
    }

    private void onMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }
}
