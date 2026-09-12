package ovh.aurumgg.guilds.core;

import java.util.Objects;
import java.util.UUID;

/** One immutable destination from a persisted guild-disband snapshot. */
public record GuildDisbandShare(
        int sequence,
        Destination destination,
        UUID player,
        long cents,
        boolean paid) {

    public enum Destination { PLAYER, TREASURY }

    public GuildDisbandShare {
        if (sequence < 0) throw new IllegalArgumentException("negative sequence");
        Objects.requireNonNull(destination, "destination");
        if (destination == Destination.PLAYER) Objects.requireNonNull(player, "player");
        if (destination == Destination.TREASURY && player != null) {
            throw new IllegalArgumentException("treasury share cannot name a player");
        }
        if (cents <= 0) throw new IllegalArgumentException("share must be positive");
    }

    public double amount() {
        return cents / 100.0;
    }

    public GuildDisbandShare asPaid() {
        return paid ? this : new GuildDisbandShare(sequence, destination, player, cents, true);
    }

    public String operationKey(long guildId) {
        return destination == Destination.TREASURY
                ? "guild-disband:" + guildId + ":treasury"
                : "guild-disband:" + guildId + ":" + player;
    }
}
