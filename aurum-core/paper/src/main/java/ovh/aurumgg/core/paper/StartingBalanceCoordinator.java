package ovh.aurumgg.core.paper;

import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import ovh.aurumgg.core.api.TransactionResult;
import ovh.aurumgg.core.engine.StartingBalanceRepository.Decision;
import ovh.aurumgg.core.engine.StartingBalanceService;

/** One small online queue; no synchronous SQL, no offline sweeps and no per-player timers. */
final class StartingBalanceCoordinator implements Listener {
    private final AurumCorePlugin plugin;
    private final StartingBalanceService service;
    private final Map<UUID, Attempt> pending = new HashMap<>();
    private long nextWarning;
    StartingBalanceCoordinator(AurumCorePlugin plugin, StartingBalanceService service) {
        this.plugin = plugin; this.service = service;
    }
    void join(Player player) {
        Attempt attempt = new Attempt(player.hasPlayedBefore(), player.getFirstPlayed());
        pending.put(player.getUniqueId(), attempt);
        observe(player.getUniqueId(), attempt);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { pending.remove(event.getPlayer().getUniqueId()); }
    private void observe(UUID id, Attempt attempt) {
        attempt.busy = true;
        service.observe(id, attempt.playedBefore, attempt.firstPlayed).whenComplete((decision, error) -> main(() -> {
            if (pending.get(id) != attempt) return;
            attempt.busy = false;
            if (error != null) { retry(attempt, "Could not record starting balance decision"); return; }
            if (!decision.pending()) { pending.remove(id); return; }
            attempt.decision = decision;
        }));
    }
    void tick() {
        long now = System.currentTimeMillis();
        for (var entry : List.copyOf(pending.entrySet())) {
            UUID id = entry.getKey(); Attempt attempt = entry.getValue();
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isOnline()) { pending.remove(id); continue; }
            if (attempt.busy || attempt.nextTry > now) continue;
            if (attempt.decision == null) { observe(id, attempt); continue; }
            if (!authenticated(id)) continue;
            attempt.busy = true;
            service.grant(attempt.decision).whenComplete((result, error) -> main(() -> {
                if (pending.get(id) != attempt) return;
                attempt.busy = false;
                if (error != null || result.status() == TransactionResult.Status.UNAVAILABLE
                        || result.status() == TransactionResult.Status.REJECTED) {
                    retry(attempt, "Starting balance pending; check database, currency and financial policies"
                            + (result == null ? "" : ": " + result.message()));
                    return;
                }
                pending.remove(id);
                Player online = plugin.getServer().getPlayer(id);
                if (online != null && result.status() == TransactionResult.Status.SUCCESS)
                    online.sendMessage(plugin.messages().component("starting-balance-received", Map.of(
                            "amount", result.netAmount().stripTrailingZeros().toPlainString(),
                            "currency", attempt.decision.currency())));
            }));
        }
    }
    private boolean authenticated(UUID id) {
        var auth = plugin.getServer().getPluginManager().getPlugin("AurumAuth");
        if (auth == null) return true;
        if (!auth.isEnabled()) return false;
        // Optional API loaded from the provider's classloader; no bundled duplicate auth API.
        for (Class<?> api : plugin.getServer().getServicesManager().getKnownServices()) {
            if (!api.getName().equals("ovh.aurumgg.auth.api.AurumAuthApi")) continue;
            Object provider = plugin.getServer().getServicesManager().load(api);
            if (provider == null) return false;
            try { return Boolean.TRUE.equals(api.getMethod("isAuthenticated", UUID.class).invoke(provider, id)); }
            catch (ReflectiveOperationException | RuntimeException failure) { return false; }
        }
        return false;
    }
    private void retry(Attempt attempt, String message) {
        attempt.nextTry = System.currentTimeMillis() + 60_000;
        if (System.currentTimeMillis() >= nextWarning) {
            nextWarning = System.currentTimeMillis() + 60_000;
            plugin.getLogger().warning(message + "; retry in 60 seconds.");
        }
    }
    private void main(Runnable task) {
        if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, task);
    }
    private static final class Attempt {
        final boolean playedBefore; final long firstPlayed;
        boolean busy; long nextTry; Decision decision;
        Attempt(boolean playedBefore, long firstPlayed) { this.playedBefore = playedBefore; this.firstPlayed = firstPlayed; }
    }
}
