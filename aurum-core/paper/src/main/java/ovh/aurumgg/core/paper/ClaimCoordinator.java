package ovh.aurumgg.core.paper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import ovh.aurumgg.core.api.ClaimResult;
import ovh.aurumgg.core.api.ClaimSnapshot;
import ovh.aurumgg.core.engine.ClaimService;

/**
 * {@code /aurum claims} — the human end of the quarantine.
 *
 * <p>A quarantined claim is a delivery the server owes a player and could not
 * complete on its own. Nothing here retries automatically: that already
 * happened and already failed, which is precisely why an administrator is now
 * looking at it. The two answers are "try again, the cause is fixed"
 * ({@code retry}) and "this will never be delivered" ({@code drop}).
 */
final class ClaimCoordinator {

    private final AurumCorePlugin plugin;
    private final ClaimService claims;

    ClaimCoordinator(AurumCorePlugin plugin, ClaimService claims) {
        this.plugin = plugin;
        this.claims = claims;
    }

    boolean execute(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "list" -> list(sender, args.length >= 3 ? args[2] : "");
            case "inspect" -> inspect(sender, args);
            case "retry" -> act(sender, args, "claims-retried", id -> claims.requeue(id));
            case "drop" -> act(sender, args, "claims-dropped",
                    id -> claims.drop(id, "dropped by " + sender.getName()));
            default -> usage(sender);
        };
    }

    private boolean list(CommandSender sender, String plugins) {
        claims.quarantined(plugins, 20).whenComplete((found, error) -> reply(() -> {
            if (error != null) {
                sender.sendMessage(plugin.messages().component("claims-unavailable"));
                return;
            }
            if (found.isEmpty()) {
                sender.sendMessage(plugin.messages().component("claims-empty"));
                return;
            }
            sender.sendMessage(plugin.messages().component("claims-header",
                    Map.of("count", Integer.toString(found.size()))));
            for (ClaimSnapshot claim : found) sender.sendMessage(line(claim));
        }));
        return true;
    }

    private boolean inspect(CommandSender sender, String[] args) {
        UUID id = parse(sender, args);
        if (id == null) return true;
        claims.inspect(id).whenComplete((found, error) -> reply(() -> {
            ClaimSnapshot claim = error != null || found == null ? null : found.orElse(null);
            if (claim == null) {
                sender.sendMessage(plugin.messages().component("claims-not-found"));
                return;
            }
            sender.sendMessage(line(claim));
            // The payload is the plugin's own format and may be long; the point
            // of showing it is that an administrator can hand it back to whoever
            // wrote it, not that it reads nicely here.
            sender.sendMessage(plugin.messages().component("claims-payload",
                    Map.of("payload", shorten(claim.payload()))));
        }));
        return true;
    }

    private boolean act(CommandSender sender, String[] args, String successKey, Action action) {
        UUID id = parse(sender, args);
        if (id == null) return true;
        action.run(id).whenComplete((result, error) -> reply(() -> {
            if (error != null || result == null || result.status() == ClaimResult.Status.UNAVAILABLE) {
                sender.sendMessage(plugin.messages().component("claims-unavailable"));
            } else if (result.status() == ClaimResult.Status.NOT_FOUND) {
                sender.sendMessage(plugin.messages().component("claims-not-found"));
            } else if (!result.ok()) {
                sender.sendMessage(plugin.messages().component("claims-conflict"));
            } else {
                sender.sendMessage(plugin.messages().component(successKey,
                        Map.of("claim", id.toString())));
            }
        }));
        return true;
    }

    private UUID parse(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender);
            return null;
        }
        try {
            return UUID.fromString(args[2]);
        } catch (IllegalArgumentException invalid) {
            sender.sendMessage(plugin.messages().component("claims-bad-id"));
            return null;
        }
    }

    private net.kyori.adventure.text.Component line(ClaimSnapshot claim) {
        return plugin.messages().component("claims-entry", Map.of(
                "claim", claim.id().toString(),
                "plugin", claim.plugin(),
                "kind", claim.kind(),
                "owner", owner(claim.owner()),
                "progress", claim.stepCursor() + "/" + claim.stepCount(),
                "attempts", Integer.toString(claim.attempts()),
                "summary", claim.summary().isBlank() ? "-" : claim.summary(),
                "error", claim.lastError().isBlank() ? "-" : claim.lastError()));
    }

    /** Name if the server has ever seen them, id otherwise: the claim outlives the profile cache. */
    private static String owner(UUID player) {
        String name = Bukkit.getOfflinePlayer(player).getName();
        return name == null ? player.toString() : name;
    }

    private static String shorten(String payload) {
        if (payload == null || payload.isBlank()) return "-";
        return payload.length() <= 200 ? payload : payload.substring(0, 200) + "…";
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(plugin.messages().component("claims-usage"));
        return true;
    }

    private void reply(Runnable action) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, action);
    }

    List<String> actions() {
        return List.of("list", "inspect", "retry", "drop");
    }

    @FunctionalInterface
    private interface Action {
        java.util.concurrent.CompletionStage<ClaimResult> run(UUID claimId);
    }
}
