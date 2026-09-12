package dev.addons.npc.service;

import org.bukkit.entity.Player;

/**
 * What one durable claim owes, broken into steps that are always attempted in
 * the same order.
 *
 * <p>A claim remembers progress as a single cursor, which only means anything
 * if the steps are ordered: step 2 must never run before step 1. That is the
 * whole contract a plan has to keep.
 *
 * <p>Steps may be asynchronous. A plan must call exactly one method on the
 * {@link Outcome} it is handed — eventually, and exactly once. Calling none
 * leaves the claim leased until the lease expires; calling two would advance
 * the cursor past a step that did not happen.
 */
public interface DeliveryPlan {

    /** Matches the claim's kind in Core, and picks the decoder on the way back. */
    String kind();

    int stepCount();

    /** One line an administrator can read in {@code /aurum claims}. */
    String summary();

    /** Plugin-defined; Core stores it and never parses it. */
    String encode();

    /**
     * Whether this step changes the player's persisted Minecraft data.
     *
     * <p>The delivery loop stamps those steps into the same player data as the
     * inventory mutation. If the server stops after the item moved but before
     * Core records the cursor, the stamp lets the retry advance without moving
     * the item a second time. Database-backed and arbitrary command steps must
     * stay false: their effects do not share the player's persistence domain.
     */
    default boolean playerDataStep(int index) { return false; }

    void step(Player player, int index, Outcome outcome);

    /**
     * The claim was dropped without anything being owed.
     *
     * <p>Cleanup that belongs to the plan rather than to the delivery loop —
     * putting shop stock back, for instance.
     */
    default void onAbandoned() {}

    /** How a step reports what happened. */
    interface Outcome {
        /** The step took effect. Move the cursor. */
        void done();

        /** Not now, but plausibly later — inventory full, player logged off. */
        void defer(String reason);

        /**
         * Nothing was owed after all: no money moved and no items changed
         * hands. The claim is closed and forgotten, not handed to a human.
         */
        void abandon(String reason);

        /** Delivery cannot work and needs a person to look at it. */
        void quarantine(String reason);
    }
}
