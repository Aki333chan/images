package dev.addons.npc.model;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;

public final class GuildBonusOffer {
    private final int slot;
    private GuildBonusType type;
    private double magnitude;
    private long durationSeconds;
    private double price;
    private Material icon;
    private String displayName;
    private String permission = "";
    private final List<String> lore = new ArrayList<>();

    public GuildBonusOffer(int slot, GuildBonusType type, double magnitude, long durationSeconds, double price) {
        this.slot = slot;
        this.type = type;
        this.magnitude = type.validateMagnitude(magnitude);
        durationSeconds(durationSeconds);
        price(price);
        this.icon = type.defaultIcon();
        this.displayName = "&6&l" + type.title();
    }

    public int slot() { return slot; }
    public GuildBonusType type() { return type; }
    public void type(GuildBonusType type) { this.type = type; magnitude(type.validateMagnitude(magnitude)); }
    public double magnitude() { return magnitude; }
    public void magnitude(double magnitude) { this.magnitude = type.validateMagnitude(magnitude); }
    public long durationSeconds() { return durationSeconds; }
    public void durationSeconds(long durationSeconds) {
        if (durationSeconds < 0) throw new IllegalArgumentException("Duration cannot be negative.");
        this.durationSeconds = durationSeconds;
    }
    public boolean permanent() { return durationSeconds == 0; }
    public double price() { return price; }
    public void price(double price) {
        if (!Double.isFinite(price) || price < 0) throw new IllegalArgumentException("Price cannot be negative.");
        this.price = price;
    }
    public Material icon() { return icon; }
    public void icon(Material icon) { this.icon = icon == null ? type.defaultIcon() : icon; }
    public String displayName() { return displayName; }
    public void displayName(String displayName) { this.displayName = displayName; }
    public String permission() { return permission; }
    public void permission(String permission) { this.permission = permission == null ? "" : permission; }
    public List<String> lore() { return lore; }
}
