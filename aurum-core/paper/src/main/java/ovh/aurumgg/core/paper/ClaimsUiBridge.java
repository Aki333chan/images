package ovh.aurumgg.core.paper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ovh.aurumgg.core.api.ClaimResult;
import ovh.aurumgg.core.api.ClaimSnapshot;
import ovh.aurumgg.core.engine.ClaimService;

/** Thin, request-driven AurumUI view over the durable claim quarantine. */
final class ClaimsUiBridge {
    private static final int LIMIT = 50;

    private final AurumCorePlugin plugin;
    private final ClaimService claims;

    ClaimsUiBridge(AurumCorePlugin plugin, ClaimService claims) {
        this.plugin = plugin;
        this.claims = claims;
    }

    CompletionStage<List<Map<String, String>>> snapshot(Player viewer) {
        if (!allowed(viewer)) return CompletableFuture.completedFuture(List.of());
        return claims.quarantined("", LIMIT)
                .thenCompose(found -> onMain(() -> found.stream().map(this::object).toList()));
    }

    Object action(Player viewer, String rawId, String action) {
        if (!allowed(viewer)) return "error.permission";
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (RuntimeException invalid) {
            return "error.claims.not-found";
        }
        CompletionStage<ClaimResult> result = switch (action) {
            case "retry" -> claims.requeue(id);
            case "drop" -> claims.drop(id, "dropped by " + viewer.getName() + " through AurumUI");
            default -> null;
        };
        if (result == null) return "error.unknown_action";
        return result.thenApply(done -> message(action, done))
                .exceptionally(error -> "error.claims.unavailable");
    }

    private boolean allowed(Player viewer) {
        return plugin.activeReady() && viewer.hasPermission("aurum.admin.claims");
    }

    private Map<String, String> object(ClaimSnapshot claim) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", claim.id().toString());
        fields.put("kind", "claim");
        fields.put("title", claim.plugin() + " · " + claim.kind());
        fields.put("plugin", claim.plugin());
        fields.put("claimKind", claim.kind());
        fields.put("owner", owner(claim.owner()));
        fields.put("progress", claim.stepCursor() + "/" + claim.stepCount());
        fields.put("attempts", Integer.toString(claim.attempts()));
        fields.put("summary", claim.summary().isBlank() ? "-" : shorten(claim.summary()));
        fields.put("error", claim.lastError().isBlank() ? "-" : shorten(claim.lastError()));
        fields.put("payload", shorten(claim.payload()));
        fields.put("created", claim.createdAt().toString());
        fields.put("actions", "retry,drop");
        return Map.copyOf(fields);
    }

    private static String message(String action, ClaimResult result) {
        if (result == null || result.status() == ClaimResult.Status.UNAVAILABLE) {
            return "error.claims.unavailable";
        }
        if (result.status() == ClaimResult.Status.NOT_FOUND) return "error.claims.not-found";
        if (!result.ok()) return "error.claims.conflict";
        return action.equals("drop") ? "claims.dropped" : "claims.retried";
    }

    private static String owner(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? id.toString() : name;
    }

    private static String shorten(String value) {
        if (value == null || value.isBlank()) return "-";
        return value.length() <= 240 ? value : value.substring(0, 240) + "…";
    }

    private <T> CompletionStage<T> onMain(java.util.function.Supplier<T> supplier) {
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
}
