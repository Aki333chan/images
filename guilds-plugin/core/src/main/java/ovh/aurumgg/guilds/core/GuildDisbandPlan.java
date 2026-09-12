package ovh.aurumgg.guilds.core;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** The frozen policy, balance and recipients of one guild disband. */
public record GuildDisbandPlan(
        long guildId,
        BankOnDisband mode,
        long totalCents,
        Instant createdAt,
        List<GuildDisbandShare> shares) {

    public GuildDisbandPlan {
        if (guildId <= 0) throw new IllegalArgumentException("invalid guild id");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(createdAt, "createdAt");
        shares = List.copyOf(shares);
        if (totalCents < 0) throw new IllegalArgumentException("negative balance");
        long allocated = 0;
        HashSet<Integer> sequences = new HashSet<>();
        for (GuildDisbandShare share : shares) {
            if (!sequences.add(share.sequence())) {
                throw new IllegalArgumentException("duplicate share sequence");
            }
            allocated = Math.addExact(allocated, share.cents());
        }
        if (mode == BankOnDisband.KEEP && allocated != 0) {
            throw new IllegalArgumentException("keep plan cannot have payouts");
        }
        if (mode != BankOnDisband.KEEP && allocated != totalCents) {
            throw new IllegalArgumentException("payouts do not equal frozen balance");
        }
        if (mode == BankOnDisband.TREASURY
                && (shares.size() != 1
                || shares.getFirst().destination() != GuildDisbandShare.Destination.TREASURY)) {
            throw new IllegalArgumentException("treasury plan must have one treasury payout");
        }
        if ((mode == BankOnDisband.LEADER || mode == BankOnDisband.SPLIT)
                && shares.stream().anyMatch(share ->
                share.destination() != GuildDisbandShare.Destination.PLAYER)) {
            throw new IllegalArgumentException("player plan contains a non-player destination");
        }
        if (mode == BankOnDisband.LEADER && shares.size() != 1) {
            throw new IllegalArgumentException("leader plan must have one payout");
        }
    }

    public boolean complete() {
        return shares.stream().allMatch(GuildDisbandShare::paid);
    }

    public GuildDisbandPlan markPaid(int sequence) {
        return new GuildDisbandPlan(guildId, mode, totalCents, createdAt,
                shares.stream().map(share -> share.sequence() == sequence ? share.asPaid() : share)
                        .toList());
    }
}
