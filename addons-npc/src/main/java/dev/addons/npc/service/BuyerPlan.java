package dev.addons.npc.service;

import dev.addons.npc.config.BuyerRepository;
import dev.addons.npc.model.BuyerDefinition;
import dev.addons.npc.model.BuyerOffer;
import java.util.Map;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionResult;

/**
 * Delivery of one buyer sale: take the items, pay for them, run the commands.
 *
 * <p>The order is the opposite of a shop's and the reason is the same. Taking
 * the items is the irreversible half, so the claim is written first: a crash
 * after the items are gone resumes at the payment, and a crash before them
 * finds nothing taken and drops the claim. The old flow had no such record and
 * lost the PLAYER's items — a reservation that quietly expired while the goods
 * were already gone.
 */
public final class BuyerPlan implements DeliveryPlan {

    public static final String KIND = "BUYER_SALE";

    private final JavaPlugin plugin;
    private final EconomyService economy;
    private final MessageService messages;
    private final BuyerRepository buyers;
    private final ClaimDelivery delivery;
    private final SaleClaim sale;

    public BuyerPlan(JavaPlugin plugin, EconomyService economy, MessageService messages,
                     BuyerRepository buyers, ClaimDelivery delivery, SaleClaim sale) {
        this.plugin = plugin;
        this.economy = economy;
        this.messages = messages;
        this.buyers = buyers;
        this.delivery = delivery;
        this.sale = sale;
    }

    @Override public String kind() { return KIND; }
    @Override public int stepCount() { return sale.stepCount(); }
    @Override public String summary() { return sale.summary(); }
    @Override public String encode() { return sale.encode(); }
    @Override public boolean playerDataStep(int index) { return sale.itemStep(index); }
    @Override public boolean advanceBeforeEffect(int index) {
        return sale.commandAt(index).map(ClaimCommand::advanceBeforeEffect).orElse(false);
    }

    @Override
    public void step(Player player, int index, Outcome outcome) {
        if (sale.itemStep(index)) {
            takeItems(player, outcome);
            return;
        }
        if (sale.paymentStep(index)) {
            pay(player, outcome);
            return;
        }
        Optional<ClaimCommand> command = sale.commandAt(index);
        if (command.isEmpty()) {
            outcome.quarantine("step " + index + " is not part of this sale");
            return;
        }
        ClaimCommandRunner.run(plugin, "Buyer", command.get(), outcome);
    }

    /**
     * Take exactly what was quoted, or nothing at all.
     *
     * <p>Every way this can fail means the sale never happened: nothing has
     * been paid yet and nothing has been taken, so the claim is dropped rather
     * than handed to an administrator. A sale that simply did not go through is
     * not a debt.
     */
    private void takeItems(Player player, Outcome outcome) {
        if (sale.deadline() > 0 && System.currentTimeMillis() > sale.deadline()) {
            // Protects the player, not the money: a sale clicked before a crash
            // and resumed a week later would take items they have long since
            // decided to keep.
            messages.send(player, "buyer-sale-expired");
            cancelUntouchedSale(outcome, "sale expired before the items could be taken");
            return;
        }
        BuyerDefinition buyer = buyers.get(sale.buyerId());
        BuyerOffer offer = buyer == null ? null : sale.stockCycle().isBlank()
                ? buyer.offers().get(sale.slot())
                : buyer.offers().values().stream()
                        .filter(value -> value.inventory().cycle().equals(sale.stockCycle()))
                        .findFirst().orElse(null);
        if (offer == null) {
            cancelUntouchedSale(outcome, "the buyer offer no longer exists");
            return;
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        if (BuyerService.countMatching(contents, offer) < sale.amount()) {
            messages.send(player, "buyer-sale-items-gone");
            cancelUntouchedSale(outcome, "the items are no longer in the player's inventory");
            return;
        }
        if (BuyerService.removeMatching(contents, offer, sale.amount()) != sale.amount()) {
            // Counted enough a line ago and could not take them: something else
            // is holding the inventory. Worth another attempt, not a drop.
            outcome.defer("could not take the quoted items");
            return;
        }
        player.getInventory().setStorageContents(contents);
        player.updateInventory();
        outcome.done();
    }

    /**
     * Pay what was quoted.
     *
     * <p>The items are already gone by now, so this step must not give up
     * quietly. Anything short of a completed transfer is deferred: the debt is
     * real, and Core being briefly unavailable is not a reason to forget it.
     */
    private void pay(Player player, Outcome outcome) {
        if (sale.reservedBudget()) {
            settleReservation(player, outcome);
            return;
        }
        directPayment(player, outcome);
    }

    private void settleReservation(Player player, Outcome outcome) {
        economy.hold(sale.holdKey()).whenComplete((found, failure) -> delivery.onMain(() -> {
            if (failure != null || found == null) {
                outcome.defer("AurumCore hold lookup is unavailable");
                return;
            }
            if (found.isEmpty()) {
                // A very old/cleaned reservation is no reason to erase a durable debt.
                directPayment(player, outcome);
                return;
            }
            var hold = found.orElseThrow();
            if (hold.status() == ovh.aurumgg.core.api.HoldSnapshot.Status.CAPTURED) {
                paid(player, outcome);
                return;
            }
            if (hold.status() == ovh.aurumgg.core.api.HoldSnapshot.Status.HELD) {
                economy.capture(hold, sale.paymentKey()).whenComplete((result, error) ->
                        delivery.onMain(() -> finishCapture(player, result, error, outcome)));
                return;
            }
            // Expiry/release can only happen after an outage or manual intervention.
            // The claim remains authoritative and falls back to the same finite source.
            directPayment(player, outcome);
        }));
    }

    private void finishCapture(Player player, ovh.aurumgg.core.api.HoldResult result,
                               Throwable error, Outcome outcome) {
        if (error == null && result != null && (result.status() == ovh.aurumgg.core.api.HoldResult.Status.SUCCESS
                || result.status() == ovh.aurumgg.core.api.HoldResult.Status.DUPLICATE)
                && result.hold().isPresent()
                && result.hold().orElseThrow().status() == ovh.aurumgg.core.api.HoldSnapshot.Status.CAPTURED) {
            paid(player, outcome);
            return;
        }
        if (result != null && result.hold().isPresent()
                && (result.hold().orElseThrow().status() == ovh.aurumgg.core.api.HoldSnapshot.Status.EXPIRED
                || result.hold().orElseThrow().status() == ovh.aurumgg.core.api.HoldSnapshot.Status.RELEASED)) {
            directPayment(player, outcome);
            return;
        }
        outcome.defer(result == null ? "AurumCore is unavailable" : result.message());
    }

    private void directPayment(Player player, Outcome outcome) {
        Map<String, String> metadata = Map.of("plugin", ClaimGateway.PLUGIN,
                "operation", sale.operation(), "buyer", sale.buyerId(),
                "offer-slot", Integer.toString(sale.slot()));
        economy.pay(sale.paymentKey(), sale.budgetAccount(), AccountId.player(player.getUniqueId()), sale.payout(),
                TransactionCategory.NPC_SALE, metadata).whenComplete((result, error) ->
                delivery.onMain(() -> {
            if (error == null && result != null && (result.status() == TransactionResult.Status.SUCCESS
                    || result.status() == TransactionResult.Status.DUPLICATE)) {
                paid(player, outcome);
                return;
            }
            if (result != null && result.status() == TransactionResult.Status.REJECTED) {
                // The player's items are already gone. A permanent policy/status
                // rejection needs an administrator rather than an endless retry loop.
                outcome.quarantine("payout rejected: " + result.message());
                return;
            }
            outcome.defer("AurumCore is unavailable");
        }));
    }

    private void paid(Player player, Outcome outcome) {
        messages.send(player, "buyer-sale-paid", Map.of(
                "amount", economy.format(sale.payout().doubleValue())));
        outcome.done();
    }

    @Override public void onAbandoned() {
        BuyerDefinition buyer = buyers.get(sale.buyerId());
        if (buyer == null || sale.stockCycle().isBlank()) return;
        buyer.offers().values().stream()
                .filter(offer -> offer.inventory().cycle().equals(sale.stockCycle()))
                .findFirst().ifPresent(offer -> {
                    offer.inventory().restore(sale.amount(), sale.stockCycle(), System.currentTimeMillis());
                    buyers.save();
                });
    }

    private void cancelUntouchedSale(Outcome outcome, String reason) {
        if (!sale.reservedBudget()) {
            outcome.abandon(reason);
            return;
        }
        economy.hold(sale.holdKey()).whenComplete((found, failure) -> delivery.onMain(() -> {
            if (failure != null || found == null) {
                outcome.defer("could not inspect buyer budget reservation");
                return;
            }
            if (found.isEmpty() || found.orElseThrow().status() == ovh.aurumgg.core.api.HoldSnapshot.Status.RELEASED
                    || found.orElseThrow().status() == ovh.aurumgg.core.api.HoldSnapshot.Status.EXPIRED) {
                outcome.abandon(reason);
                return;
            }
            if (found.orElseThrow().status() == ovh.aurumgg.core.api.HoldSnapshot.Status.CAPTURED) {
                outcome.quarantine("buyer payout was captured before item removal");
                return;
            }
            economy.release(found.orElseThrow()).whenComplete((released, releaseFailure) ->
                    delivery.onMain(() -> {
                        if (releaseFailure == null && released != null && released.hold().isPresent()
                                && (released.hold().orElseThrow().status()
                                == ovh.aurumgg.core.api.HoldSnapshot.Status.RELEASED
                                || released.hold().orElseThrow().status()
                                == ovh.aurumgg.core.api.HoldSnapshot.Status.EXPIRED)) {
                            outcome.abandon(reason);
                        } else if (released != null && released.hold().isPresent()
                                && released.hold().orElseThrow().status()
                                == ovh.aurumgg.core.api.HoldSnapshot.Status.CAPTURED) {
                            outcome.quarantine("buyer payout was captured before item removal");
                        } else {
                            outcome.defer("could not release buyer budget reservation");
                        }
                    }));
        }));
    }
}
