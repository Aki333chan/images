package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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
final class TradeCoordinator {

    private final AurumCorePlugin plugin;
    private final TradeService trades;
    private final TradeDelivery delivery;
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
        putItem(player, trade, offers, held.clone(),
                () -> player.getInventory().setItemInMainHand(null));
    }

    /**
     * Put one stack on the table.
     *
     * <p>The stack is named by the caller rather than read from the hand,
     * because the window offers what was CLICKED. Re-reading the hand after an
     * asynchronous hop would offer whatever ended up there in the meantime.
     *
     * @param takeFromPlayer removes the stack from wherever it was; runs only
     *                       once the offer has been recorded well enough to be
     *                       written, and never before
     */
    void putItem(Player player, TradeSession trade, List<TradeOffer> offers, ItemStack offered,
                 Runnable takeFromPlayer) {
        List<ItemStack> table = new ArrayList<>(items(player.getUniqueId(), offers));
        if (table.size() >= TradeWindow.SIDE_SLOTS) {
            player.sendMessage(plugin.messages().component("trade-table-full"));
            return;
        }
        TradeOffer current = mine(player.getUniqueId(), offers);
        table.add(offered);

        byte[] blob;
        try {
            blob = TradeItems.encode(table);
        } catch (RuntimeException failure) {
            // Refusing is right: an offer whose stored half is wrong is worse
            // than an offer that could not be made. Nothing has been taken yet.
            plugin.getLogger().warning("Could not record a trade item: " + failure.getMessage());
            player.sendMessage(plugin.messages().component("trade-item-rejected"));
            return;
        }
        takeFromPlayer.run();
        player.updateInventory();

        TradeOffer updated = new TradeOffer(trade.id(), player.getUniqueId(),
                current.currencyId(), current.money(), blob, TradeItems.FORMAT);
        trades.offer(trade.id(), updated).whenComplete((result, error) -> onMain(() -> {
            if (result == null || !result.ok()) {
                // The table was closed underneath us. The item is already out of
                // the inventory, so it goes back the only durable way there is.
                returnItems(trade.id(), player.getUniqueId(), TradeItems.encode(List.of(offered)),
                        "trade-return:" + trade.id() + ":" + player.getUniqueId() + ":" + System.nanoTime());
                deliverTo(player.getUniqueId());
                answered(player, result, error);
                return;
            }
            announce(result.trade().orElse(trade), "trade-changed",
                    Map.of("player", player.getName()));
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
        trades.cancel(trade.id(), reason).whenComplete((result, error) -> onMain(() -> {
            if (result == null || !result.ok()) return;
            trades.offers(trade.id()).whenComplete((offers, offersError) -> onMain(() -> {
                if (offers != null) {
                    for (TradeOffer offer : offers) {
                        if (offer.items() == null || offer.items().length == 0) continue;
                        returnItems(trade.id(), offer.owner(), offer.items(),
                                "trade-return:" + trade.id() + ":" + offer.owner());
                    }
                }
                announce(trade, "trade-cancelled", Map.of("reason", reason));
                deliverTo(trade.first());
                deliverTo(trade.second());
            }));
        }));
    }

    /** Clear out tables nobody finished in time. */
    void sweep() {
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
