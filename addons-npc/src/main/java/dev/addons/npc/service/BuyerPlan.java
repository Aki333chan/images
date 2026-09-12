package dev.addons.npc.service;

import dev.addons.npc.config.BuyerRepository;
import dev.addons.npc.model.BuyerDefinition;
import dev.addons.npc.model.BuyerOffer;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
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

    /** Every buyer pays from here; the ledger treats a system source as always funded. */
    private static final AccountId SOURCE = new AccountId(AccountType.SYSTEM_SOURCE, "npc-buyers");

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
        Optional<String> command = sale.commandAt(index);
        if (command.isEmpty()) {
            outcome.quarantine("step " + index + " is not part of this sale");
            return;
        }
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.get());
        } catch (RuntimeException failure) {
            // Retrying an arbitrary console command is not safe, so the step
            // counts as done and the failure is loud in the log instead.
            plugin.getLogger().warning("Buyer command failed: " + failure.getMessage());
        }
        outcome.done();
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
            outcome.abandon("sale expired before the items could be taken");
            return;
        }
        BuyerDefinition buyer = buyers.get(sale.buyerId());
        BuyerOffer offer = buyer == null ? null : buyer.offers().get(sale.slot());
        if (offer == null) {
            outcome.abandon("the buyer offer no longer exists");
            return;
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        if (BuyerService.countMatching(contents, offer) < sale.amount()) {
            messages.send(player, "buyer-sale-items-gone");
            outcome.abandon("the items are no longer in the player's inventory");
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
        Map<String, String> metadata = Map.of("plugin", ClaimGateway.PLUGIN,
                "operation", sale.operation(), "buyer", sale.buyerId(),
                "offer-slot", Integer.toString(sale.slot()));
        economy.pay(sale.paymentKey(), SOURCE, AccountId.player(player.getUniqueId()), sale.payout(),
                TransactionCategory.NPC_SALE, metadata).whenComplete((result, error) ->
                delivery.onMain(() -> {
            if (error == null && result != null && (result.status() == TransactionResult.Status.SUCCESS
                    || result.status() == TransactionResult.Status.DUPLICATE)) {
                messages.send(player, "buyer-sale-paid", Map.of(
                        "amount", economy.format(sale.payout().doubleValue())));
                outcome.done();
                return;
            }
            if (result != null && result.status() == TransactionResult.Status.REJECTED) {
                // A policy rule refused a payment from a system source. Nobody
                // here can fix that, and the player's items are already gone,
                // so it goes to a person rather than round the retry loop.
                outcome.quarantine("payout rejected: " + result.message());
                return;
            }
            outcome.defer("AurumCore is unavailable");
        }));
    }
}
