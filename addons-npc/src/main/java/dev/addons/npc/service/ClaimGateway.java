package dev.addons.npc.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.core.api.AurumClaimApi;
import ovh.aurumgg.core.api.ClaimRequest;
import ovh.aurumgg.core.api.ClaimResult;
import ovh.aurumgg.core.api.ClaimSnapshot;

/** Thin hook to AurumCore's delivery claims, with this server's name as the worker. */
public final class ClaimGateway {

    /** This plugin's name in the claims table; Core never mixes two plugins' claims. */
    public static final String PLUGIN = "AddonsNPC";

    private final JavaPlugin plugin;
    private final String worker;
    private volatile AurumClaimApi claims;

    public ClaimGateway(JavaPlugin plugin) {
        this.plugin = plugin;
        // Identifies the lease holder. One Core can serve several game servers,
        // and the port is what distinguishes them when they share a host.
        this.worker = "npc@" + plugin.getServer().getPort();
    }

    public boolean hook() {
        try {
            RegisteredServiceProvider<AurumClaimApi> registration = plugin.getServer()
                    .getServicesManager().getRegistration(AurumClaimApi.class);
            claims = registration == null ? null : registration.getProvider();
        } catch (LinkageError error) {
            // Core older than 0.8.0: the class is simply not there.
            claims = null;
            plugin.getLogger().warning("AurumCore delivery claims are unavailable: "
                    + error.getClass().getSimpleName());
        }
        return claims != null;
    }

    public boolean available() {
        return claims != null;
    }

    public String worker() {
        return worker;
    }

    public CompletionStage<ClaimResult> promise(ClaimRequest request) {
        AurumClaimApi current = claims;
        return current == null ? unavailable() : current.promise(request);
    }

    public CompletionStage<List<ClaimSnapshot>> owed(UUID player) {
        AurumClaimApi current = claims;
        return current == null ? CompletableFuture.completedFuture(List.of())
                : current.owed(player, PLUGIN);
    }

    public CompletionStage<ClaimResult> take(UUID claimId, Duration lease) {
        AurumClaimApi current = claims;
        return current == null ? unavailable() : current.take(claimId, worker, lease);
    }

    public CompletionStage<ClaimResult> advance(UUID claimId, int completedSteps) {
        AurumClaimApi current = claims;
        return current == null ? unavailable() : current.advance(claimId, worker, completedSteps);
    }

    public CompletionStage<ClaimResult> settle(UUID claimId) {
        AurumClaimApi current = claims;
        return current == null ? unavailable() : current.settle(claimId, worker);
    }

    public CompletionStage<ClaimResult> defer(UUID claimId, String reason) {
        AurumClaimApi current = claims;
        return current == null ? unavailable() : current.defer(claimId, worker, reason);
    }

    public CompletionStage<ClaimResult> quarantine(UUID claimId, String reason) {
        AurumClaimApi current = claims;
        return current == null ? unavailable() : current.quarantine(claimId, worker, reason);
    }

    public CompletionStage<ClaimResult> drop(UUID claimId, String reason) {
        AurumClaimApi current = claims;
        return current == null ? unavailable() : current.drop(claimId, reason);
    }

    private static CompletionStage<ClaimResult> unavailable() {
        return CompletableFuture.completedFuture(new ClaimResult(ClaimResult.Status.UNAVAILABLE,
                java.util.Optional.empty(), "AurumCore delivery claims are unavailable"));
    }
}
