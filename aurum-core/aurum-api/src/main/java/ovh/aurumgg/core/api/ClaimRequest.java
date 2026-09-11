package ovh.aurumgg.core.api;

import java.util.Locale;
import java.util.Objects;

/**
 * A durable promise that something is owed to a player.
 *
 * <p>The payload is opaque to Core: only the plugin that wrote it knows how to
 * read it. Core guarantees the record survives a crash, that exactly one worker
 * delivers it at a time, and that progress within it is remembered.
 *
 * @param idempotencyKey stable key of the operation that created the debt; a
 *                       repeat returns the existing claim instead of a second one
 * @param plugin         who owes it, so one plugin never delivers another's claims
 * @param owner          the player the delivery is owed to
 * @param kind           plugin-defined, for grouping and for the admin listing
 * @param stepCount      how many ordered steps delivery takes; the cursor counts
 *                       completed ones, so steps must be attempted in order
 * @param summary        one line an administrator can read in {@code /aurum claims}
 * @param payload        plugin-defined and never parsed by Core
 */
public record ClaimRequest(String idempotencyKey, String plugin, java.util.UUID owner, String kind,
                           int stepCount, String summary, String payload) {

    /** Payloads are meant for a few items and commands, not for bulk storage. */
    public static final int MAX_PAYLOAD = 60_000;

    public ClaimRequest {
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").trim();
        plugin = Objects.requireNonNull(plugin, "plugin").trim();
        Objects.requireNonNull(owner, "owner");
        kind = Objects.requireNonNull(kind, "kind").trim().toUpperCase(Locale.ROOT);
        summary = Objects.requireNonNull(summary, "summary").trim();
        payload = Objects.requireNonNull(payload, "payload");
        if (idempotencyKey.isEmpty() || idempotencyKey.length() > 191
                || plugin.isEmpty() || plugin.length() > 64
                || !plugin.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")
                || kind.isEmpty() || kind.length() > 64 || !kind.matches("[A-Z0-9][A-Z0-9_]*")
                || stepCount < 1 || stepCount > 1024
                || summary.length() > 255 || summary.chars().anyMatch(Character::isISOControl)
                || payload.length() > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Invalid claim request");
        }
    }
}
