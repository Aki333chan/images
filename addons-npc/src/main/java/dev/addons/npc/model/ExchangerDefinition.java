package dev.addons.npc.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ExchangerDefinition {
    private final String id;
    private String title;
    private int size;
    private final Map<Integer, ExchangeOffer> offers = new LinkedHashMap<>();

    public ExchangerDefinition(String id, String title, int size) {
        this.id = NpcDefinition.normalizeId(id);
        title(title);
        size(size);
    }

    public String id() { return id; }
    public String title() { return title; }
    public int size() { return size; }
    public Map<Integer, ExchangeOffer> offers() { return offers; }
    public void title(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException("Invalid exchanger title");
        title = value;
    }
    public void size(int value) {
        if (value < 9 || value > 54 || value % 9 != 0) throw new IllegalArgumentException("Exchanger size must be 9..54 in rows of 9");
        if (offers.keySet().stream().anyMatch(slot -> slot >= value)) throw new IllegalArgumentException("Offers exist outside the new size");
        size = value;
    }
}
