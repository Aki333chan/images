package dev.addons.npc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimCommandTest {

    @Test
    void legacyCommandIsAtMostOnceAndNeedsNoMigration() {
        ClaimCommand command = ClaimCommand.prepare("/say hello", "operation:0");

        assertEquals(ClaimCommand.Mode.AT_MOST_ONCE, command.mode());
        assertEquals("say hello", command.command());
        assertTrue(command.advanceBeforeEffect());
        assertEquals(command, ClaimCommand.stored(command.encode()));
    }

    @Test
    void explicitOncePrefixHasTheSameSafeNoDuplicateContract() {
        ClaimCommand command = ClaimCommand.prepare("ONCE:/broadcast done", "operation:0");

        assertEquals(ClaimCommand.Mode.AT_MOST_ONCE, command.mode());
        assertEquals("broadcast done", command.command());
    }

    @Test
    void idempotentCommandGetsAStableKeyAndMayRunBeforeCursorAdvance() {
        ClaimCommand command = ClaimCommand.prepare(
                "idempotent:rewards grant Steve 5 {idempotency_key}", "npc-command:abc:2");

        assertEquals(ClaimCommand.Mode.IDEMPOTENT, command.mode());
        assertEquals("rewards grant Steve 5 npc-command:abc:2", command.command());
        assertFalse(command.advanceBeforeEffect());
        assertEquals(command, ClaimCommand.stored(command.encode()));
    }

    @Test
    void idempotentOptInWithoutReceiverKeyIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ClaimCommand.prepare("idempotent:give Steve diamond", "npc-command:abc:0"));

        assertTrue(error.getMessage().contains(ClaimCommand.KEY_PLACEHOLDER));
    }

    @Test
    void everyCommandInOneClaimGetsADifferentFrozenKey() {
        List<ClaimCommand> commands = ClaimCommand.prepareAll(List.of(
                "idempotent:test {idempotency_key}",
                "idempotent:test {idempotency_key}"), "npc-command:abc");

        assertEquals("test npc-command:abc:0", commands.get(0).command());
        assertEquals("test npc-command:abc:1", commands.get(1).command());
    }
}
