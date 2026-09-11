package dev.addons.npc.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Material;

public final class ExchangeOffer {
    private final int slot;
    private String fromCurrency;
    private String toCurrency;
    private BigDecimal sourceAmount;
    private Material icon = Material.EMERALD;
    private String displayName = "&aCurrency exchange";
    private String permission = "";
    private final List<String> lore = new ArrayList<>();

    public ExchangeOffer(int slot, String fromCurrency, String toCurrency, BigDecimal sourceAmount) {
        if (slot < 0 || slot >= 54) throw new IllegalArgumentException("Exchange slot must be between 0 and 53");
        this.slot = slot;
        currencies(fromCurrency, toCurrency);
        amount(sourceAmount);
    }

    public int slot() { return slot; }
    public String fromCurrency() { return fromCurrency; }
    public String toCurrency() { return toCurrency; }
    public BigDecimal sourceAmount() { return sourceAmount; }
    public Material icon() { return icon; }
    public String displayName() { return displayName; }
    public String permission() { return permission; }
    public List<String> lore() { return lore; }

    public void currencies(String from, String to) {
        from = normalizeCurrency(from);
        to = normalizeCurrency(to);
        if (from.equals(to)) throw new IllegalArgumentException("Exchange currencies must differ");
        this.fromCurrency = from;
        this.toCurrency = to;
    }
    public void amount(BigDecimal value) {
        value = Objects.requireNonNull(value, "sourceAmount");
        if (value.signum() <= 0 || value.precision() > 24 || value.scale() > 8) {
            throw new IllegalArgumentException("Invalid exchange source amount");
        }
        sourceAmount = value;
    }
    public void icon(Material value) {
        if (value == null || !value.isItem()) throw new IllegalArgumentException("Invalid exchange icon");
        icon = value;
    }
    public void displayName(String value) { displayName = Objects.requireNonNull(value, "displayName"); }
    public void permission(String value) { permission = Objects.requireNonNullElse(value, ""); }

    private static String normalizeCurrency(String value) {
        String normalized = Objects.requireNonNull(value, "currency").trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,31}")) throw new IllegalArgumentException("Invalid currency id");
        return normalized;
    }
}
