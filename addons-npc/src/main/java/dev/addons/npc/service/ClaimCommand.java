package dev.addons.npc.service;

import java.util.ArrayList;
import java.util.List;

/**
 * A console-command claim step with an explicit crash contract.
 *
 * <p>An arbitrary Bukkit command cannot be made exactly-once by wrapping it in
 * another database transaction: its effect belongs to another plugin. Legacy
 * and {@code once:} commands are therefore at-most-once and their claim cursor
 * is committed before dispatch. {@code idempotent:} commands are retried after
 * a crash, but only after the administrator explicitly gives their receiver a
 * stable {@value #KEY_PLACEHOLDER}.
 */
public record ClaimCommand(Mode mode, String command) {

    public static final String KEY_PLACEHOLDER = "{idempotency_key}";
    private static final String ONCE_PREFIX = "once:";
    private static final String IDEMPOTENT_PREFIX = "idempotent:";

    public ClaimCommand {
        if (mode == null) throw new IllegalArgumentException("Command mode is required");
        command = stripSlash(command == null ? "" : command.trim());
        if (command.isBlank()) throw new IllegalArgumentException("Command cannot be empty");
    }

    public enum Mode {
        /** Cursor first, effect second: never duplicated, but a crash can skip it. */
        AT_MOST_ONCE,
        /** Effect first, cursor second: receiver must deduplicate the stable key. */
        IDEMPOTENT
    }

    public boolean advanceBeforeEffect() {
        return mode == Mode.AT_MOST_ONCE;
    }

    /** Turn one configured line into the immutable form stored in a claim. */
    public static ClaimCommand prepare(String configured, String idempotencyKey) {
        String raw = configured == null ? "" : configured.trim();
        if (startsWith(raw, IDEMPOTENT_PREFIX)) {
            String body = raw.substring(IDEMPOTENT_PREFIX.length()).trim();
            if (!body.contains(KEY_PLACEHOLDER)) {
                throw new IllegalArgumentException(
                        "An idempotent command must contain " + KEY_PLACEHOLDER);
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("An idempotent command needs a stable key");
            }
            return new ClaimCommand(Mode.IDEMPOTENT, body.replace(KEY_PLACEHOLDER, idempotencyKey));
        }
        if (startsWith(raw, ONCE_PREFIX)) raw = raw.substring(ONCE_PREFIX.length()).trim();
        // Unprefixed legacy lines deliberately become the safer no-duplicate
        // mode. Existing shops need no config migration.
        return new ClaimCommand(Mode.AT_MOST_ONCE, raw);
    }

    public static List<ClaimCommand> prepareAll(List<String> configured, String keyPrefix) {
        List<ClaimCommand> result = new ArrayList<>();
        List<String> source = configured == null ? List.of() : configured;
        for (int index = 0; index < source.size(); index++) {
            result.add(prepare(source.get(index), keyPrefix + ":" + index));
        }
        return List.copyOf(result);
    }

    /** Read the immutable line from a claim; plain schema-1 lines stay compatible. */
    public static ClaimCommand stored(String encoded) {
        String raw = encoded == null ? "" : encoded.trim();
        if (startsWith(raw, IDEMPOTENT_PREFIX)) {
            return new ClaimCommand(Mode.IDEMPOTENT, raw.substring(IDEMPOTENT_PREFIX.length()));
        }
        if (startsWith(raw, ONCE_PREFIX)) raw = raw.substring(ONCE_PREFIX.length());
        return new ClaimCommand(Mode.AT_MOST_ONCE, raw);
    }

    public String encode() {
        return (mode == Mode.IDEMPOTENT ? IDEMPOTENT_PREFIX : ONCE_PREFIX) + command;
    }

    private static boolean startsWith(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String stripSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }
}
