package dev.addons.npc.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GuildTraderDefinition {
    private final String id;
    private String title;
    private int size;
    private GuildRankRequirement requiredRank = GuildRankRequirement.OFFICER;
    private final Map<Integer, GuildBonusOffer> offers = new LinkedHashMap<>();

    public GuildTraderDefinition(String id, String title, int size) {
        this.id = NpcDefinition.normalizeId(id);
        this.title = title;
        size(size);
    }

    public String id() { return id; }
    public String title() { return title; }
    public void title(String title) { this.title = title; }
    public int size() { return size; }
    public void size(int size) {
        int clamped = Math.max(9, Math.min(54, size));
        this.size = ((clamped + 8) / 9) * 9;
    }
    public GuildRankRequirement requiredRank() { return requiredRank; }
    public void requiredRank(GuildRankRequirement requiredRank) {
        this.requiredRank = requiredRank == null ? GuildRankRequirement.OFFICER : requiredRank;
    }
    public Map<Integer, GuildBonusOffer> offers() { return offers; }
}
