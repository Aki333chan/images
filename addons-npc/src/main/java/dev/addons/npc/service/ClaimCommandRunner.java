package dev.addons.npc.service;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Dispatches a command according to the crash contract frozen in its claim. */
final class ClaimCommandRunner {
    private ClaimCommandRunner() {}

    static void run(JavaPlugin plugin, String context, ClaimCommand command, DeliveryPlan.Outcome outcome) {
        try {
            boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.command());
            if (!accepted && command.mode() == ClaimCommand.Mode.IDEMPOTENT) {
                outcome.defer(context + " command was not accepted");
                return;
            }
            if (!accepted) plugin.getLogger().warning(context + " command was not accepted: " + command.command());
            outcome.done();
        } catch (RuntimeException failure) {
            if (command.mode() == ClaimCommand.Mode.IDEMPOTENT) {
                // The receiver promised to deduplicate its stable key, so a
                // retry is safe even if it applied the effect before throwing.
                outcome.defer(context + " idempotent command failed: " + failure.getMessage());
                return;
            }
            // The cursor is already committed. Retrying could duplicate an
            // arbitrary effect; report loudly and continue.
            plugin.getLogger().warning(context + " at-most-once command failed: " + failure.getMessage());
            outcome.done();
        }
    }
}
