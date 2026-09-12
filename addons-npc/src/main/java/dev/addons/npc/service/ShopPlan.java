package dev.addons.npc.service;

import dev.addons.npc.config.ShopRepository;
import dev.addons.npc.model.ShopDefinition;
import dev.addons.npc.model.ShopOffer;
import java.util.HashMap;
import java.util.Optional;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.HoldSnapshot;

/**
 * Delivery of one shop purchase: collect the money, hand over the item, run the
 * commands.
 *
 * <p>Payment is step zero rather than a precondition, and that is the point.
 * The record has to exist BEFORE the money moves: if the money moved first, a
 * crash in between would leave a player charged for a purchase nothing knows
 * about — the very failure this exists to prevent, moved one step earlier.
 */
public final class ShopPlan implements DeliveryPlan {

    public static final String KIND = "SHOP_PURCHASE";

    private final JavaPlugin plugin;
    private final EconomyService economy;
    private final MessageService messages;
    private final ShopRepository shops;
    private final ClaimDelivery delivery;
    private final PurchaseClaim purchase;

    public ShopPlan(JavaPlugin plugin, EconomyService economy, MessageService messages,
                    ShopRepository shops, ClaimDelivery delivery, PurchaseClaim purchase) {
        this.plugin = plugin;
        this.economy = economy;
        this.messages = messages;
        this.shops = shops;
        this.delivery = delivery;
        this.purchase = purchase;
    }

    @Override public String kind() { return KIND; }
    @Override public int stepCount() { return purchase.stepCount(); }
    @Override public String summary() { return purchase.summary(); }
    @Override public String encode() { return purchase.encode(); }
    @Override public boolean playerDataStep(int index) { return index == purchase.itemStep(); }
    @Override public boolean advanceBeforeEffect(int index) {
        return purchase.commandAt(index).map(ClaimCommand::advanceBeforeEffect).orElse(false);
    }

    @Override
    public void step(Player player, int index, Outcome outcome) {
        if (purchase.paymentStep(index)) {
            // Stable per purchase, so a repeat of the same capture comes back a
            // duplicate rather than a second charge.
            collect("npc-claim:" + purchase.holdKey().orElseThrow(), outcome);
            return;
        }
        Optional<ClaimCommand> command = purchase.commandAt(index);
        if (command.isPresent()) {
            ClaimCommandRunner.run(plugin, "Shop", command.get(), outcome);
            return;
        }
        giveItem(player, outcome);
    }

    private void giveItem(Player player, Outcome outcome) {
        ItemStack reward = purchase.item().clone();
        ItemStack[] before = java.util.Arrays.stream(player.getInventory().getStorageContents())
                .map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        if (!ShopService.canFit(before, reward)) {
            // Deferring counts an attempt, and that is correct: a player who
            // never frees a slot should end up in quarantine rather than have
            // this retried on every login for ever.
            messages.send(player, "delivery-inventory-full");
            outcome.defer("inventory full");
            return;
        }
        HashMap<Integer, ItemStack> leftovers = new HashMap<>(player.getInventory().addItem(reward));
        if (!leftovers.isEmpty()) {
            // Restore the exact inventory image. Removing an indistinguishable
            // stack could otherwise take an older item that the player already
            // owned rather than the part inserted by this delivery.
            player.getInventory().setStorageContents(before);
            outcome.defer("inventory changed during delivery");
            return;
        }
        player.updateInventory();
        outcome.done();
    }

    /**
     * Take the money that was reserved when the player clicked.
     *
     * <p>Captured from the snapshot Core returns, never from values kept here:
     * Core refuses a capture that differs from the reservation by even one
     * metadata entry, and refusing after the money is reserved is the worst
     * moment to find that out.
     */
    private void collect(String key, Outcome outcome) {
        economy.hold(purchase.holdKey().orElse("")).whenComplete((found, error) ->
                delivery.onMain(() -> {
            if (error != null) {
                outcome.defer("AurumCore did not answer");
                return;
            }
            HoldSnapshot hold = found == null ? null : found.orElse(null);
            if (hold == null || hold.status() == HoldSnapshot.Status.RELEASED
                    || hold.status() == HoldSnapshot.Status.EXPIRED
                    || hold.status() == HoldSnapshot.Status.REJECTED) {
                // Nothing was ever charged, so nothing is owed. Dropping is the
                // honest answer; quarantining would put a debt that does not
                // exist in front of an administrator.
                outcome.abandon(hold == null ? "reservation is gone"
                        : "reservation " + hold.status().name().toLowerCase(java.util.Locale.ROOT));
                return;
            }
            if (hold.status() == HoldSnapshot.Status.CAPTURED) {
                outcome.done();
                return;
            }
            economy.capture(hold, key).whenComplete((result, failure) -> delivery.onMain(() -> {
                if (failure == null && result != null && (result.status() == HoldResult.Status.SUCCESS
                        || result.status() == HoldResult.Status.DUPLICATE)) {
                    outcome.done();
                } else if (result != null && result.status() == HoldResult.Status.REJECTED) {
                    // The reservation and the capture no longer describe the
                    // same operation. Money did not move, so there is nothing
                    // to hand over and nothing to settle.
                    outcome.abandon("capture rejected: " + result.message());
                } else {
                    outcome.defer("AurumCore is unavailable");
                }
            }));
        }));
    }

    /**
     * The purchase never happened: put the stock back.
     *
     * <p>Stock was taken when the claim was written, because at that moment the
     * player was about to be charged. When the charge turns out never to have
     * happened, the shelf has to go back the way it was.
     */
    @Override
    public void onAbandoned() {
        try {
            ShopDefinition shop = shops.get(purchase.shopId());
            ShopOffer offer = shop == null ? null : shop.offers().get(purchase.slot());
            if (offer == null || offer.unlimited()) return;
            offer.stock(offer.stock() + purchase.item().getAmount());
            shops.save();
        } catch (RuntimeException failure) {
            // Stock is not money. Losing one purchase quantity is a drift
            // worth a log line, not a reason to leave the claim open.
            plugin.getLogger().log(Level.WARNING, "Could not restore shop stock", failure);
        }
    }
}
