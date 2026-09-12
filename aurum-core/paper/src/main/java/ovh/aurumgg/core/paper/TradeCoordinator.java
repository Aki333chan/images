package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.ClaimResult;
import ovh.aurumgg.core.api.TradeOffer;
import ovh.aurumgg.core.api.TradeResult;
import ovh.aurumgg.core.api.TradeSession;
import ovh.aurumgg.core.api.TradeState;
import ovh.aurumgg.core.engine.TradeService;

/**
 * {@code /trade} — the player-facing half of a guaranteed trade.
 *
 * <h2>Where the offered items actually are</h2>
 *
 * Nowhere physical, and that is the design. An item put on the table leaves the
 * player's inventory immediately and exists only as the durable blob in Core.
 * Keeping a live copy somewhere as well would mean two places that can disagree
 * — and the moment they disagree, one of them is a duplicated item.
 *
 * Everything that ends a trade therefore ends it the same way: the blob becomes
 * a claim owed to somebody. Settlement owes each side's table to the other;
 * cancelling and timing out owe each table back to its owner.
 *
 * <h2>Why commands and not a window, for now</h2>
 *
 * The safety of a trade lives in the revision, not in the pixels: every edit
 * invalidates both confirmations, and a confirmation names the revision it was
 * given for. Commands exercise exactly that machinery, so the window can be
 * added later as a skin over a service that already works, instead of being the
 * place the rules are written down.
 */
final class TradeCoordinator implements Listener {

    private final AurumCorePlugin plugin;
    private final TradeService trades;
    private final TradeDelivery delivery;
    private final TradeDepositReceipt deposits;
    /** Players with a command in flight; two clicks must not race one table. */
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    /** Trades whose durable settlement pipeline is already running on this server. */
    private final Set<UUID> settling = ConcurrentHashMap.newKeySet();
    /**
     * Кому сообщать, что стол изменился.
     *
     * Окно — оболочка над этим сервисом, а не второе место, где записаны
     * правила. Поэтому оно не опрашивает состояние, а получает его тогда же,
     * когда игрокам уходит сообщение: правку сделал СОСЕД, и перерисовать надо
     * обоим.
     */
    private volatile java.util.function.Consumer<TradeSession> onChanged = trade -> {};

    TradeCoordinator(AurumCorePlugin plugin, TradeService trades, TradeDelivery delivery) {
        this.plugin = plugin;
        this.trades = trades;
        this.delivery = delivery;
        this.deposits = new TradeDepositReceipt(plugin);
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.messages().component("player-only"));
            return true;
        }
        String action = args.length == 0 ? "view" : args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "accept" -> withTrade(player, TradeState.INVITED, (trade, offers) -> accept(player, trade));
            case "item" -> withOpen(player, (trade, offers) -> putItem(player, trade, offers));
            case "money" -> withOpen(player, (trade, offers) -> putMoney(player, trade, offers, args));
            case "clear" -> withOpen(player, (trade, offers) -> clear(player, trade, offers));
            case "confirm" -> withOpen(player, (trade, offers) -> confirm(player, trade));
            case "cancel" -> withAny(player, trade -> cancel(trade, "cancelled by " + player.getName()));
            case "view" -> view(player);
            case "window" -> openWindow(player);
            default -> invite(player, args[0]);
        };
    }

    // --------------------------------------------------------------- AurumUI

    /** Request-driven trade state. No polling task and no Bukkit access off-thread. */
    CompletionStage<List<Map<String, String>>> uiSnapshot(Player player) {
        if (!player.hasPermission("aurum.trade")) return CompletableFuture.completedFuture(List.of());
        if (!plugin.activeReady()) return CompletableFuture.completedFuture(
                List.of(uiStatus("error.trade.unavailable", false)));
        if (!plugin.settings().tradingEnabled()) return CompletableFuture.completedFuture(
                List.of(uiStatus("error.trade.disabled", false)));
        if (deposits.has(player)) {
            recoverDeposit(player, false);
            return CompletableFuture.completedFuture(List.of(uiStatus("error.trade.unavailable", false)));
        }
        return trades.activeFor(player.getUniqueId()).thenCompose(found -> {
            TradeSession trade = found == null ? null : found.orElse(null);
            if (trade == null) return onMainValue(() -> List.of(uiInvite()));
            return trades.offers(trade.id()).thenCompose(offers -> onMainValue(() ->
                    List.of(uiTrade(player, trade, offers == null ? List.of() : offers))));
        }).exceptionally(error -> List.of(uiStatus("error.trade.unavailable", false)));
    }

    /** Typed UI actions over the same TradeService used by commands and the inventory window. */
    Object uiAction(Player player, String id, String action, Map<String, String> arguments) {
        if (!plugin.activeReady() || !plugin.settings().tradingEnabled()) return "error.trade.disabled";
        if (!player.hasPermission("aurum.trade")) return "error.permission";
        if (deposits.has(player)) {
            recoverDeposit(player, false);
            return "error.trade.unavailable";
        }
        return switch (action) {
            case "invite" -> uiInvite(player, arguments.get("player"));
            case "accept" -> uiAccept(player, id);
            case "open" -> uiOpen(player);
            case "money" -> uiMoney(player, id, arguments.get("amount"));
            case "confirm" -> uiConfirm(player, id, arguments.get("revision"));
            case "cancel" -> uiCancel(player, id);
            default -> "error.unknown_action";
        };
    }

    private CompletionStage<String> uiInvite(Player player, String targetName) {
        Player target = targetName == null ? null : Bukkit.getPlayerExact(targetName.trim());
        if (target == null) return CompletableFuture.completedFuture("error.trade.player-offline");
        return trades.invite(player.getUniqueId(), target.getUniqueId())
                .thenCompose(result -> onMainValue(() -> {
                    if (!result.ok()) return uiFailure(result);
                    player.sendMessage(plugin.messages().component("trade-invited", Map.of("player", target.getName())));
                    target.sendMessage(plugin.messages().component("trade-invite-received", Map.of("player", player.getName())));
                    return "trade.invited";
                })).exceptionally(error -> "error.trade.unavailable");
    }

    private CompletionStage<String> uiAccept(Player player, String rawId) {
        return active(player, rawId, TradeState.INVITED).thenCompose(trade -> {
            if (trade.isEmpty()) return CompletableFuture.completedFuture("error.trade.changed");
            return trades.accept(trade.get().id(), player.getUniqueId()).thenCompose(result ->
                    onMainValue(() -> {
                        if (!result.ok()) return uiFailure(result);
                        announce(result.trade().orElse(trade.get()), "trade-opened", Map.of());
                        return "trade.accepted";
                    }));
        }).exceptionally(error -> "error.trade.unavailable");
    }

    private String uiOpen(Player player) {
        openWindow(player);
        return "trade.opening";
    }

    private CompletionStage<String> uiMoney(Player player, String rawId, String rawAmount) {
        CurrencySpec currency = plugin.settings().currency();
        BigDecimal amount;
        try {
            amount = currency.requireAmount(new BigDecimal(rawAmount == null ? "" : rawAmount.trim()));
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture("error.trade.amount");
        }
        if (amount.signum() < 0) return CompletableFuture.completedFuture("error.trade.amount");
        BigDecimal selected = amount;
        return active(player, rawId, TradeState.OPEN).thenCompose(trade -> {
            if (trade.isEmpty()) return CompletableFuture.completedFuture("error.trade.changed");
            return trades.offers(trade.get().id()).thenCompose(offers -> {
                List<TradeOffer> currentOffers = offers == null ? List.of() : offers;
                TradeOffer current = mine(player.getUniqueId(), currentOffers);
                TradeOffer updated = new TradeOffer(trade.get().id(), player.getUniqueId(),
                        selected.signum() > 0 ? Optional.of(currency.id()) : Optional.empty(), selected,
                        current.items(), current.itemsFormatVersion());
                return trades.offer(trade.get().id(), updated).thenCompose(result -> onMainValue(() -> {
                    if (!result.ok()) return uiFailure(result);
                    announce(result.trade().orElse(trade.get()), "trade-changed", Map.of("player", player.getName()));
                    return "trade.changed";
                }));
            });
        }).exceptionally(error -> "error.trade.unavailable");
    }

    private CompletionStage<String> uiConfirm(Player player, String rawId, String rawRevision) {
        UUID id;
        long revision;
        try {
            id = UUID.fromString(rawId);
            revision = Long.parseLong(rawRevision);
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture("error.trade.changed");
        }
        return trades.confirm(id, player.getUniqueId(), revision).thenCompose(result -> onMainValue(() -> {
            if (!result.ok()) return uiFailure(result);
            TradeSession confirmed = result.trade().orElseThrow();
            announce(confirmed, "trade-confirmed", Map.of("player", player.getName()));
            if (confirmed.ready()) settle(confirmed);
            return "trade.confirmed";
        })).exceptionally(error -> "error.trade.unavailable");
    }

    private CompletionStage<String> uiCancel(Player player, String rawId) {
        return active(player, rawId, null).thenCompose(trade -> {
            if (trade.isEmpty()) return CompletableFuture.completedFuture("error.trade.changed");
            return cancelAsync(trade.get(), "cancelled by " + player.getName() + " through AurumUI")
                    .thenApply(result -> result.ok() ? "trade.cancelled" : uiFailure(result));
        }).exceptionally(error -> "error.trade.unavailable");
    }

    private CompletionStage<Optional<TradeSession>> active(Player player, String rawId, TradeState required) {
        UUID expected;
        try {
            expected = UUID.fromString(rawId);
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return trades.activeFor(player.getUniqueId()).thenApply(found -> found.filter(trade ->
                trade.id().equals(expected) && (required == null || trade.state() == required)));
    }

    private Map<String, String> uiInvite() {
        return Map.of("id", "invite", "kind", "trade", "title", "",
                "titleKey", "screen.aurumui.trade.new", "state", "NONE", "actions", "invite");
    }

    private Map<String, String> uiTrade(Player player, TradeSession trade, List<TradeOffer> offers) {
        UUID viewerId = player.getUniqueId();
        UUID other = trade.other(viewerId);
        TradeOffer myOffer = mine(viewerId, offers);
        TradeOffer theirOffer = mine(other, offers);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", trade.id().toString());
        fields.put("kind", "trade");
        fields.put("title", name(other));
        fields.put("state", trade.state().name());
        fields.put("revision", Long.toString(trade.revision()));
        fields.put("mineConfirmed", Boolean.toString(trade.confirmed(viewerId)));
        fields.put("otherConfirmed", Boolean.toString(trade.confirmed(other)));
        fields.put("mineMoney", myOffer.money().stripTrailingZeros().toPlainString());
        fields.put("otherMoney", theirOffer.money().stripTrailingZeros().toPlainString());
        fields.put("symbol", plugin.settings().currency().symbol());
        fields.put("mineItems", shorten(TradeItems.describe(items(viewerId, offers)), 900));
        fields.put("otherItems", shorten(TradeItems.describe(items(other, offers)), 900));
        fields.put("expires", trade.expiresAt().toString());
        String actions = switch (trade.state()) {
            case INVITED -> trade.second().equals(viewerId) ? "accept,cancel" : "cancel";
            case OPEN -> "open,money,confirm,cancel";
            default -> "";
        };
        fields.put("actions", actions);
        return Map.copyOf(fields);
    }

    private static Map<String, String> uiStatus(String message, boolean success) {
        return Map.of("id", "status", "kind", "status", "title", "",
                "message", message, "success", Boolean.toString(success));
    }

    private static String shorten(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
    }

    private static String uiFailure(TradeResult result) {
        if (result == null) return "error.trade.unavailable";
        return switch (result.status()) {
            case BUSY -> "error.trade.busy";
            case NOT_FOUND -> "error.trade.none";
            case INSUFFICIENT_FUNDS -> "error.trade.insufficient";
            case UNAVAILABLE -> "error.trade.unavailable";
            default -> "error.trade.changed";
        };
    }

    private <T> CompletionStage<T> onMainValue(java.util.function.Supplier<T> supplier) {
        if (Bukkit.isPrimaryThread()) return CompletableFuture.completedFuture(supplier.get());
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(supplier.get());
            } catch (RuntimeException failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    // ------------------------------------------------------------- действия

    private boolean invite(Player player, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            player.sendMessage(plugin.messages().component("player-not-online"));
            return true;
        }
        trades.invite(player.getUniqueId(), target.getUniqueId()).whenComplete((result, error) ->
                onMain(() -> {
            if (!answered(player, result, error)) return;
            player.sendMessage(plugin.messages().component("trade-invited",
                    Map.of("player", target.getName())));
            target.sendMessage(plugin.messages().component("trade-invite-received",
                    Map.of("player", player.getName())));
        }));
        return true;
    }

    private void accept(Player player, TradeSession trade) {
        trades.accept(trade.id(), player.getUniqueId()).whenComplete((result, error) -> onMain(() -> {
            if (!answered(player, result, error)) return;
            announce(result.trade().orElse(trade), "trade-opened", Map.of());
        }));
    }

    /**
     * Put what is in hand on the table.
     *
     * <p>The stack leaves the inventory in the same tick the offer is written.
     * Leaving it in hand "until settlement" is how the same sword gets traded to
     * two people.
     */
    private void putItem(Player player, TradeSession trade, List<TradeOffer> offers) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            player.sendMessage(plugin.messages().component("trade-nothing-in-hand"));
            return;
        }
        ItemStack offered = held.clone();
        putItem(player, trade, offers, offered, () -> {
            ItemStack current = player.getInventory().getItemInMainHand();
            if (!sameAmountOrMore(current, offered)) return false;
            decrement(current, offered.getAmount(),
                    value -> player.getInventory().setItemInMainHand(value));
            return true;
        });
    }

    /**
     * Put one stack on the table.
     *
     * <p>The stack is named by the caller rather than read from the hand,
     * because the window offers what was CLICKED. Re-reading the hand after an
     * asynchronous hop would offer whatever ended up there in the meantime.
     *
     * @param takeFromPlayer removes the exact source stack and reports whether
     *                       it was still present; it runs on the main thread
     */
    void putItem(Player player, TradeSession trade, List<TradeOffer> offers, ItemStack offered,
                 BooleanSupplier takeFromPlayer) {
        if (deposits.has(player)) {
            recoverDeposit(player, false);
            player.sendMessage(plugin.messages().component("trade-unavailable"));
            return;
        }
        List<ItemStack> table = new ArrayList<>(items(player.getUniqueId(), offers));
        if (table.size() >= TradeWindow.SIDE_SLOTS) {
            player.sendMessage(plugin.messages().component("trade-table-full"));
            return;
        }
        TradeOffer current = mine(player.getUniqueId(), offers);
        table.add(offered);

        byte[] blob;
        byte[] itemBlob;
        try {
            blob = TradeItems.encode(table);
            itemBlob = TradeItems.encode(List.of(offered));
        } catch (RuntimeException failure) {
            // Refusing is right: an offer whose stored half is wrong is worse
            // than an offer that could not be made. Nothing has been taken yet.
            plugin.getLogger().warning("Could not record a trade item: " + failure.getMessage());
            player.sendMessage(plugin.messages().component("trade-item-rejected"));
            return;
        }
        TradeDepositReceipt.Pending pending = new TradeDepositReceipt.Pending(
                "trade-offer:" + UUID.randomUUID(), trade.id(), current.currencyId(),
                current.money(), TradeItems.FORMAT, blob, itemBlob);

        ItemStack[] inventoryBefore = java.util.Arrays.stream(player.getInventory().getStorageContents())
                .map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        ItemStack cursorBefore = player.getItemOnCursor().clone();
        try {
            deposits.mark(player, pending);
            if (!takeFromPlayer.getAsBoolean()) {
                deposits.clear(player);
                player.sendMessage(plugin.messages().component("trade-item-rejected"));
                return;
            }
            player.updateInventory();
            // The missing item and its recovery instructions reach one player
            // file before MariaDB is allowed to own the item.
            player.saveData();
        } catch (RuntimeException failure) {
            player.getInventory().setStorageContents(inventoryBefore);
            player.setItemOnCursor(cursorBefore);
            deposits.clear(player);
            player.updateInventory();
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not persist outgoing trade receipt for " + player.getUniqueId(), failure);
            player.sendMessage(plugin.messages().component("trade-item-rejected"));
            return;
        }
        submitDeposit(player, pending, trade, true);
    }

    /** Retry only when a player actually carries an unfinished receipt. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!deposits.has(event.getPlayer())) return;
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> recoverDeposit(event.getPlayer(), false), 20L);
    }

    private void recoverDeposit(Player player, boolean reportFailure) {
        if (!player.isOnline() || !deposits.has(player)) return;
        deposits.read(player).ifPresent(pending -> submitDeposit(player, pending, null, reportFailure));
    }

    private void submitDeposit(Player player, TradeDepositReceipt.Pending pending,
                               TradeSession knownTrade, boolean reportFailure) {
        UUID owner = player.getUniqueId();
        if (!busy.add(owner)) return;
        trades.offerIdempotent(pending.operationKey(), pending.tradeId(), pending.offer(owner))
                .whenComplete((result, error) -> onMain(() -> {
            if (error == null && result != null && result.ok()) {
                deposits.clear(player);
                busy.remove(owner);
                TradeSession current = result.trade().orElse(knownTrade);
                if (current != null) {
                    if (reportFailure) announce(current, "trade-changed", Map.of("player", player.getName()));
                    else onChanged.accept(current);
                }
                return;
            }
            if (error != null || result == null || result.status() == TradeResult.Status.UNAVAILABLE) {
                // The commit may have succeeded and only its answer was lost.
                // Retain the receipt and retry the same operation later.
                busy.remove(owner);
                if (reportFailure) answered(player, result, error);
                return;
            }
            // A definite conflict means MariaDB did not accept this operation.
            // Turn the removed source stack into a durable, idempotent refund.
            delivery.owe("trade-deposit-refund:" + pending.operationKey(), owner,
                    pending.tradeId(), pending.itemBlob(), pending.format(),
                    "trade item deposit rejected").whenComplete((claim, claimError) -> onMain(() -> {
                boolean durable = claimError == null && claim != null && claim.ok()
                        && claim.claim().isPresent();
                if (durable) {
                    deposits.clear(player);
                    deliverTo(owner);
                }
                busy.remove(owner);
                if (reportFailure) answered(player, result, null);
            }));
        }));
    }

    private void putMoney(Player player, TradeSession trade, List<TradeOffer> offers, String[] args) {
        CurrencySpec currency = plugin.settings().currency();
        BigDecimal amount;
        try {
            amount = currency.requireAmount(new BigDecimal(args.length >= 2 ? args[1] : "0"));
        } catch (RuntimeException invalid) {
            player.sendMessage(plugin.messages().component("trade-bad-amount"));
            return;
        }
        if (amount.signum() < 0) {
            player.sendMessage(plugin.messages().component("trade-bad-amount"));
            return;
        }
        TradeOffer current = mine(player.getUniqueId(), offers);
        TradeOffer updated = new TradeOffer(trade.id(), player.getUniqueId(),
                amount.signum() > 0 ? Optional.of(currency.id()) : Optional.empty(), amount,
                current.items(), current.itemsFormatVersion());
        trades.offer(trade.id(), updated).whenComplete((result, error) -> onMain(() -> {
            if (!answered(player, result, error)) return;
            announce(result.trade().orElse(trade), "trade-changed", Map.of("player", player.getName()));
        }));
    }

    /** Take everything back off the table. The items are owed back through a claim. */
    private void clear(Player player, TradeSession trade, List<TradeOffer> offers) {
        TradeOffer current = mine(player.getUniqueId(), offers);
        TradeOffer emptied = new TradeOffer(trade.id(), player.getUniqueId(), Optional.empty(),
                BigDecimal.ZERO, null, TradeItems.FORMAT);
        trades.offer(trade.id(), emptied).whenComplete((result, error) -> onMain(() -> {
            if (!answered(player, result, error)) return;
            if (current.items() != null && current.items().length > 0) {
                returnItems(trade.id(), player.getUniqueId(), current.items(),
                        "trade-clear:" + trade.id() + ":" + player.getUniqueId() + ":"
                                + result.trade().map(TradeSession::revision).orElse(0L));
            }
            announce(result.trade().orElse(trade), "trade-changed", Map.of("player", player.getName()));
        }));
    }

    /**
     * Confirm what is on the table right now.
     *
     * <p>The revision travels with the confirmation, so a confirmation that
     * arrives after the table changed is refused rather than applied to
     * something the player never saw.
     */
    private void confirm(Player player, TradeSession trade) {
        trades.confirm(trade.id(), player.getUniqueId(), trade.revision())
                .whenComplete((result, error) -> onMain(() -> {
            if (!answered(player, result, error)) return;
            TradeSession confirmed = result.trade().orElse(trade);
            announce(confirmed, "trade-confirmed", Map.of("player", player.getName()));
            if (confirmed.ready()) settle(confirmed);
        }));
    }

    // --------------------------------------------------------------- расчёт

    /**
     * Both sides signed off on the same table: move the money, then owe each
     * side's goods to the other.
     *
     * <p>Money first, and through Core. If it cannot be paid the trade goes back
     * on the table with nothing moved; once it is paid, the goods are a debt
     * that survives a restart.
     */
    private void settle(TradeSession trade) {
        if (!settling.add(trade.id())) return;
        CompletionStage<TradeResult> locked = trade.state() == TradeState.SETTLING
                ? CompletableFuture.completedFuture(new TradeResult(TradeResult.Status.SUCCESS,
                        Optional.of(trade), "Settlement resumed"))
                : trades.lock(trade.id());
        locked.thenCompose(result -> result != null && result.ok()
                        ? trades.settleMoney(trade.id()) : CompletableFuture.completedFuture(result))
                .whenComplete((paid, payError) -> onMain(() -> {
            if (payError != null || paid == null || !paid.ok()) {
                settling.remove(trade.id());
                announce(paid != null && paid.trade().isPresent() ? paid.trade().orElseThrow() : trade,
                        paid != null && paid.status() == TradeResult.Status.INSUFFICIENT_FUNDS
                                ? "trade-insufficient" : "trade-failed", Map.of());
                return;
            }
            createSettlementClaims(paid.trade().orElse(trade));
        }));
    }

    /** Money may already be captured; all item debts must exist before the trade is final. */
    private void createSettlementClaims(TradeSession trade) {
        trades.offers(trade.id()).whenComplete((offers, offersError) -> {
            if (offersError != null || offers == null) {
                settlementFailed(trade, offersError, "could not load offers");
                return;
            }
            List<CompletionStage<ClaimResult>> writes = new ArrayList<>();
            for (TradeOffer offer : offers) {
                if (offer.items() == null || offer.items().length == 0) continue;
                UUID recipient = trade.other(offer.owner());
                writes.add(delivery.owe("trade-settle:" + trade.id() + ":" + offer.owner(), recipient,
                        trade.id(), offer.items(), offer.itemsFormatVersion(),
                        "trade goods from " + name(offer.owner())));
            }
            CompletableFuture<?>[] futures = writes.stream()
                    .map(stage -> stage.toCompletableFuture()).toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).whenComplete((ignored, claimError) -> onMain(() -> {
                boolean durable = claimError == null && writes.stream().allMatch(stage -> {
                    ClaimResult result = stage.toCompletableFuture().getNow(null);
                    return result != null && result.ok() && result.claim().isPresent();
                });
                if (!durable) {
                    settlementFailed(trade, claimError, "could not persist every item claim");
                    return;
                }
                trades.settled(trade.id()).whenComplete((done, doneError) -> onMain(() -> {
                    settling.remove(trade.id());
                    if (doneError != null || done == null || !done.ok()) {
                        settlementFailed(trade, doneError, "could not finalize trade");
                        return;
                    }
                    announce(done.trade().orElse(trade), "trade-settled", Map.of());
                    deliverTo(trade.first());
                    deliverTo(trade.second());
                }));
            }));
        });
    }

    private void settlementFailed(TradeSession trade, Throwable error, String reason) {
        settling.remove(trade.id());
        plugin.getLogger().warning("Trade " + trade.id() + " remains SETTLING: " + reason
                + (error == null ? "" : " (" + error.getMessage() + ")"));
    }

    /** Call a trade off and give both tables back. */
    void cancel(TradeSession trade, String reason) {
        cancelAsync(trade, reason).exceptionally(error -> {
            plugin.getLogger().warning("Could not cancel trade " + trade.id() + ": " + error.getMessage());
            return null;
        });
    }

    private CompletionStage<TradeResult> cancelAsync(TradeSession trade, String reason) {
        return trades.cancel(trade.id(), reason).thenCompose(result -> {
            if (result == null || !result.ok()) return CompletableFuture.completedFuture(result);
            return trades.offers(trade.id()).thenCompose(offers -> onMainValue(() -> {
                if (offers != null) {
                    for (TradeOffer offer : offers) {
                        if (offer.items() == null || offer.items().length == 0) continue;
                        returnItems(trade.id(), offer.owner(), offer.items(),
                                "trade-return:" + trade.id() + ":" + offer.owner());
                    }
                }
                announce(result.trade().orElse(trade), "trade-cancelled", Map.of("reason", reason));
                deliverTo(trade.first());
                deliverTo(trade.second());
                return result;
            }));
        });
    }

    /** Clear out tables nobody finished in time. */
    void sweep() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (deposits.has(player)) recoverDeposit(player, false);
        }
        trades.timedOut(50).whenComplete((expired, error) -> onMain(() -> {
            if (expired == null) return;
            for (TradeSession trade : expired) cancel(trade, "timed out");
        }));
        trades.settling(50).whenComplete((pending, error) -> onMain(() -> {
            if (pending == null) return;
            for (TradeSession trade : pending) settle(trade);
        }));
        delivery.sweep();
    }

    private void returnItems(UUID tradeId, UUID owner, byte[] blob, String key) {
        if (blob == null || blob.length == 0) return;
        delivery.owe(key, owner, tradeId, blob, TradeItems.FORMAT, "trade goods returned");
    }

    // ------------------------------------------------------------- просмотр

    private volatile java.util.function.Consumer<Player> windowOpener = player -> { };

    void openWith(java.util.function.Consumer<Player> opener) {
        this.windowOpener = opener == null ? player -> { } : opener;
    }

    private boolean openWindow(Player player) {
        windowOpener.accept(player);
        return true;
    }

    private boolean view(Player player) {
        trades.activeFor(player.getUniqueId()).whenComplete((found, error) -> onMain(() -> {
            TradeSession trade = found == null ? null : found.orElse(null);
            if (trade == null) {
                player.sendMessage(plugin.messages().component("trade-none"));
                return;
            }
            trades.offers(trade.id()).whenComplete((offers, offersError) -> onMain(() ->
                    show(player, trade, offers == null ? List.of() : offers)));
        }));
        return true;
    }

    private void show(Player player, TradeSession trade, List<TradeOffer> offers) {
        UUID other = trade.other(player.getUniqueId());
        player.sendMessage(plugin.messages().component("trade-header", Map.of(
                "player", name(other),
                "state", trade.state().name().toLowerCase(Locale.ROOT),
                "revision", Long.toString(trade.revision()))));
        player.sendMessage(plugin.messages().component("trade-side", Map.of(
                "player", player.getName(),
                "money", money(mine(player.getUniqueId(), offers)),
                "items", TradeItems.describe(items(player.getUniqueId(), offers)),
                "confirmed", Boolean.toString(trade.confirmed(player.getUniqueId())))));
        player.sendMessage(plugin.messages().component("trade-side", Map.of(
                "player", name(other),
                "money", money(mine(other, offers)),
                "items", TradeItems.describe(items(other, offers)),
                "confirmed", Boolean.toString(trade.confirmed(other)))));
    }

    // ------------------------------------------------------------ служебное

    private interface WithTrade {
        void run(TradeSession trade, List<TradeOffer> offers);
    }

    private boolean withOpen(Player player, WithTrade action) {
        return withTrade(player, TradeState.OPEN, action);
    }

    private boolean withTrade(Player player, TradeState required, WithTrade action) {
        if (deposits.has(player)) {
            recoverDeposit(player, false);
            player.sendMessage(plugin.messages().component("trade-unavailable"));
            return true;
        }
        if (!busy.add(player.getUniqueId())) return true;
        trades.activeFor(player.getUniqueId()).whenComplete((found, error) -> onMain(() -> {
            busy.remove(player.getUniqueId());
            TradeSession trade = found == null ? null : found.orElse(null);
            if (trade == null || trade.state() != required) {
                player.sendMessage(plugin.messages().component(
                        trade == null ? "trade-none" : "trade-wrong-state"));
                return;
            }
            trades.offers(trade.id()).whenComplete((offers, offersError) -> onMain(() ->
                    action.run(trade, offers == null ? List.of() : offers)));
        }));
        return true;
    }

    private boolean withAny(Player player, java.util.function.Consumer<TradeSession> action) {
        trades.activeFor(player.getUniqueId()).whenComplete((found, error) -> onMain(() -> {
            TradeSession trade = found == null ? null : found.orElse(null);
            if (trade == null) {
                player.sendMessage(plugin.messages().component("trade-none"));
                return;
            }
            action.accept(trade);
        }));
        return true;
    }

    /** Report anything that is not a plain success, and say whether to go on. */
    private boolean answered(Player player, TradeResult result, Throwable error) {
        if (error != null || result == null) {
            player.sendMessage(plugin.messages().component("trade-unavailable"));
            return false;
        }
        if (result.ok()) return true;
        player.sendMessage(plugin.messages().component(switch (result.status()) {
            case BUSY -> "trade-busy";
            case NOT_FOUND -> "trade-none";
            case INSUFFICIENT_FUNDS -> "trade-insufficient";
            case UNAVAILABLE -> "trade-unavailable";
            default -> "trade-changed-warning";
        }));
        return false;
    }

    void notifyChanges(java.util.function.Consumer<TradeSession> listener) {
        this.onChanged = listener == null ? trade -> {} : listener;
    }

    /** Забрать со стола один конкретный предмет — то, чего командой не сделать. */
    void takeBack(Player player, TradeSession trade, List<TradeOffer> offers, int index) {
        List<ItemStack> table = new ArrayList<>(items(player.getUniqueId(), offers));
        if (index < 0 || index >= table.size()) return;
        ItemStack removed = table.remove(index);
        TradeOffer current = mine(player.getUniqueId(), offers);
        TradeOffer updated = new TradeOffer(trade.id(), player.getUniqueId(), current.currencyId(),
                current.money(), TradeItems.encode(table), TradeItems.FORMAT);
        trades.offer(trade.id(), updated).whenComplete((result, error) -> onMain(() -> {
            if (!answered(player, result, error)) return;
            // Через заявку, а не прямо в инвентарь: запись в базу и изменение
            // инвентаря не бывают одной транзакцией, и здесь то же правило,
            // что и везде.
            returnItems(trade.id(), player.getUniqueId(), TradeItems.encode(List.of(removed)),
                    "trade-take:" + trade.id() + ":" + player.getUniqueId() + ":"
                            + result.trade().map(TradeSession::revision).orElse(0L));
            deliverTo(player.getUniqueId());
            announce(result.trade().orElse(trade), "trade-changed", Map.of("player", player.getName()));
        }));
    }

    /**
     * Подтвердить ту ревизию, которую игрок ВИДЕЛ, а не текущую.
     *
     * Окно рисуется по снимку; между отрисовкой и кликом сосед мог поменять
     * стол. Отправить сюда текущую ревизию значило бы подтвердить вместо игрока
     * то, чего он не видел, — ровно тот обман, от которого ревизия и защищает.
     */
    void confirmDrawn(Player player, UUID tradeId, long drawnRevision) {
        trades.confirm(tradeId, player.getUniqueId(), drawnRevision)
                .whenComplete((result, error) -> onMain(() -> {
            if (!answered(player, result, error)) return;
            TradeSession confirmed = result.trade().orElseThrow();
            announce(confirmed, "trade-confirmed", Map.of("player", player.getName()));
            if (confirmed.ready()) settle(confirmed);
        }));
    }

    /** Оферты этого стола — для отрисовки окна. */
    void refresh(TradeSession trade, java.util.function.BiConsumer<TradeSession, List<TradeOffer>> sink) {
        trades.offers(trade.id()).whenComplete((offers, error) -> onMain(() ->
                sink.accept(trade, offers == null ? List.of() : offers)));
    }

    /** Открытая сделка игрока вместе с офертами, или сообщение о том, что её нет. */
    void current(Player player, java.util.function.BiConsumer<TradeSession, List<TradeOffer>> sink) {
        withOpen(player, sink::accept);
    }

    private void announce(TradeSession trade, String key, Map<String, String> values) {
        onChanged.accept(trade);
        for (UUID side : List.of(trade.first(), trade.second())) {
            Player online = Bukkit.getPlayer(side);
            if (online != null) online.sendMessage(plugin.messages().component(key, values));
        }
    }

    private void deliverTo(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) delivery.drain(online);
    }

    private static TradeOffer mine(UUID owner, List<TradeOffer> offers) {
        return offers.stream().filter(offer -> offer.owner().equals(owner)).findFirst()
                .orElseGet(() -> new TradeOffer(offers.isEmpty() ? UUID.randomUUID()
                        : offers.getFirst().tradeId(), owner, Optional.empty(), BigDecimal.ZERO,
                        null, TradeItems.FORMAT));
    }

    private static List<ItemStack> items(UUID owner, List<TradeOffer> offers) {
        TradeOffer offer = mine(owner, offers);
        return TradeItems.decode(offer.items(), offer.itemsFormatVersion()).orElse(List.of());
    }

    static boolean sameAmountOrMore(ItemStack current, ItemStack expected) {
        return current != null && !current.getType().isAir()
                && current.isSimilar(expected) && current.getAmount() >= expected.getAmount();
    }

    private static void decrement(ItemStack current, int amount,
                                  java.util.function.Consumer<ItemStack> setter) {
        int left = current.getAmount() - amount;
        if (left <= 0) setter.accept(null);
        else {
            ItemStack changed = current.clone();
            changed.setAmount(left);
            setter.accept(changed);
        }
    }

    private String money(TradeOffer offer) {
        return offer.hasMoney()
                ? offer.money().stripTrailingZeros().toPlainString() + " "
                        + plugin.settings().currency().symbol()
                : "-";
    }

    private static String name(UUID player) {
        String known = Bukkit.getOfflinePlayer(player).getName();
        return known == null ? player.toString() : known;
    }

    private void onMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }

    List<String> actions() {
        return List.of("accept", "item", "money", "clear", "confirm", "cancel", "view", "window");
    }
}
