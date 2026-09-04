package dev.addons.npc.model;

import java.util.Locale;

/** Minimum AurumGuilds rank allowed to replace a guild-wide bonus. */
public enum GuildRankRequirement {
    MEMBER(0), OFFICER(1), LEADER(2);

    private final int weight;

    GuildRankRequirement(int weight) { this.weight = weight; }
    public int weight() { return weight; }

    public static GuildRankRequirement parse(String raw) {
        if (raw == null) return OFFICER;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Guild rank must be member, officer or leader.");
        }
    }
}
