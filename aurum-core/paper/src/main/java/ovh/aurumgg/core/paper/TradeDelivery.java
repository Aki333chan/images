package ovh.aurumgg.core.paper;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.core.api.ClaimRequest;
import ovh.aurumgg.core.api.ClaimSnapshot;
import ovh.aurumgg.core.engine.ClaimService;

/**
 * Handing trade items to whoever they now belong to.
 *
 * <h2>Why items never go straight into an inventory</h2>
 *
 * A trade ends in one of three ways — it settles, it is called off, or it times
 * out — and all three end with items owed to someone who may be offline, whose
 * inventory may be full, or whose server may be about to restart. Putting them
 * anywhere but a durable claim would mean choosing which of those cases to lose
 * items in.
 *
 * <p>So both directions go through the same path: the table's blob becomes a
 * claim, and the claim is delivered when it can be. Settlement and cancellation
 * differ only in whose name is on it.
 */
final class TradeDelivery implements Listener {

    static final String KIND = "TRADE_ITEMS";
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final Plugin plugin;
    private final ClaimService claims;
    private final LanguageBundle messages;
    private final String worker;
    private final PlayerDeliveryReceipt receipts;
    /** Claims this server is already delivering; the lease covers the other servers. */
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    TradeDelivery(Plugin plugin, ClaimService claims, LanguageBundle messages) {
        this.plugin = plugin;
        this.claims = claims;
        this.messages = messages;
        this.receipts = new PlayerDeliveryReceipt(plugin);
        this.worker = "trade@" + (plugin.getServer().getWorlds().isEmpty()
                ? plugin.getServer().getName() + ":" + plugin.getServer().getPort()
                : plugin.getServer().getWorlds().getFirst().getUID());
    }

    /**
     * Record that these items are owed to this player.
     *
     * @param key stable per trade and per recipient: settling twice, or
     *            cancelling a trade that was already cancelled, must not hand
     *            the same goods over twice
     */
    java.util.concurrent.CompletionStage<ovh.aurumgg.core.api.ClaimResult> owe(
            String key, UUID recipient, UUID tradeId, byte[] items, int format, String summary) {
        YamlConfiguration payload = new YamlConfiguration();
        payload.set("trade", tradeId.toString());
        payload.set("format", format);
        payload.set("items", items == null ? "" : Base64.getEncoder().encodeToString(items));
        return claims.promise(new ClaimRequest(key, "AurumCore", recipient, KIND, 1,
                summary, payload.saveToString()));
    }

    // ------------------------------------------------------------- доставка

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // A tick later: on join the inventory is restored but not yet settled,
        // and "will this fit" answered too early is answered wrongly.
        Bukkit.getScheduler().runTaskLater(plugin, () -> drain(event.getPlayer()), 20L);
    }

    void sweep() {
        for (Player player : Bukkit.getOnlinePlayers()) drain(player);
    }

    void drain(Player player) {
        if (!player.isOnline()) return;
        UUID owner = player.getUniqueId();
        claims.owed(owner, "AurumCore").whenComplete((owed, error) -> onMain(() -> {
            if (error != null || owed == null) return;
            Player online = Bukkit.getPlayer(owner);
            if (online == null) return;
            List<ClaimSnapshot> mine = owed.stream().filter(claim -> KIND.equals(claim.kind())).toList();
            receipts.clean(online, mine.stream().map(ClaimSnapshot::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            if (mine.isEmpty()) return;
            online.sendMessage(messages.component("trade-delivery-pending",
                    Map.of("count", Integer.toString(mine.size()))));
            for (ClaimSnapshot claim : mine) deliver(online, claim);
        }));
    }

    /**
     * Lease first, read the payload second.
     *
     * <p>Quarantining needs the lease, so an unreadable blob can only be set
     * aside from inside one. Reading first would leave it PENDING for ever,
     * retried at every login and never seen by a person.
     */
    private void deliver(Player player, ClaimSnapshot claim) {
        if (!running.add(claim.id())) return;
        claims.take(claim.id(), worker, LEASE).whenComplete((taken, error) -> onMain(() -> {
            if (error != null || taken == null || !taken.ok()) {
                running.remove(claim.id());
                return;
            }
            List<ItemStack> items = read(taken.claim().orElse(claim));
            if (items == null) {
                plugin.getLogger().warning("Trade claim " + claim.id() + " could not be read and was "
                        + "quarantined; see /aurum claims");
                close(claim.id(), claims.quarantine(claim.id(), worker, "trade items could not be read"));
                return;
            }
            give(player, claim.id(), items);
        }));
    }

    private void give(Player player, UUID claimId, List<ItemStack> items) {
        if (receipts.has(player, claimId)) {
            // The inventory and receipt reached player.dat together, but the
            // claim cursor did not reach MariaDB. Complete only the missing
            // database half instead of handing over the table twice.
            advance(player, claimId);
            return;
        }
        // All or nothing. A half-delivered trade would need the claim to
        // remember which stacks already went in, and the whole point of the
        // blob is that the table is one indivisible thing.
        if (!fits(player, items)) {
            player.sendMessage(messages.component("trade-delivery-full"));
            close(claimId, claims.defer(claimId, worker, "inventory full"));
            return;
        }
        ItemStack[] before = java.util.Arrays.stream(player.getInventory().getStorageContents())
                .map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        for (ItemStack item : items) {
            HashMap<Integer, ItemStack> leftovers = new HashMap<>(
                    player.getInventory().addItem(item.clone()));
            if (!leftovers.isEmpty()) {
                // The inventory changed between the check and the insert. Take
                // back what did go in and try again later: an item handed over
                // twice is worse than one handed over late.
                player.getInventory().setStorageContents(before);
                close(claimId, claims.defer(claimId, worker, "inventory changed during delivery"));
                return;
            }
        }
        player.updateInventory();
        receipts.mark(player, claimId);
        advance(player, claimId);
    }

    private void advance(Player player, UUID claimId) {
        try {
            // Keep the cross-store order strict: the inventory and receipt
            // must be durable before MariaDB is allowed to forget this step.
            // This is one targeted save per completed item hand-off, never a
            // periodic or join-time disk sweep.
            player.saveData();
        } catch (RuntimeException failure) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not persist trade delivery receipt for claim " + claimId, failure);
            close(claimId, claims.defer(claimId, worker,
                    "could not persist the player's inventory"));
            return;
        }
        claims.advance(claimId, worker, 1).whenComplete((advanced, error) -> onMain(() -> {
            if (error != null || advanced == null || !advanced.ok()) {
                // The lease is gone, so this server is no longer the one
                // delivering. The step is recorded or it is not; guessing would
                // repeat it.
                running.remove(claimId);
                return;
            }
            receipts.clear(player, claimId);
            close(claimId, claims.settle(claimId, worker));
        }));
    }

    /**
     * Would the whole table fit?
     *
     * <p>Asked against a copy of the inventory, because asking it stack by stack
     * as they go in is how half a trade ends up delivered.
     */
    private static boolean fits(Player player, List<ItemStack> items) {
        org.bukkit.inventory.Inventory probe = Bukkit.createInventory(null, 36);
        probe.setContents(player.getInventory().getStorageContents());
        for (ItemStack item : items) {
            if (!probe.addItem(item.clone()).isEmpty()) return false;
        }
        return true;
    }

    private List<ItemStack> read(ClaimSnapshot claim) {
        try {
            YamlConfiguration payload = new YamlConfiguration();
            payload.loadFromString(claim.payload());
            String encoded = payload.getString("items", "");
            byte[] blob = encoded.isBlank() ? null : Base64.getDecoder().decode(encoded);
            Optional<List<ItemStack>> items = TradeItems.decode(blob, payload.getInt("format", 0));
            return items.map(java.util.ArrayList::new).orElse(null);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private void close(UUID claimId, java.util.concurrent.CompletionStage<?> action) {
        action.whenComplete((result, error) -> running.remove(claimId));
    }

    private void onMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }
}
