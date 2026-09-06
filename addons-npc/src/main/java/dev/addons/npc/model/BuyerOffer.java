package dev.addons.npc.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class BuyerOffer {
    public enum MatchMode {
        MATERIAL,
        EXACT;

        public static MatchMode parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Match mode must be material or exact.");
            }
        }
    }

    public enum SaleMode { UNIT_ONE, UNIT_ALL, BULK_ONE, BULK_ALL }

    public record SaleQuote(int amount, double payout) {
        public SaleQuote {
            if (amount < 1 || !Double.isFinite(payout) || payout <= 0) {
                throw new IllegalArgumentException("Invalid sale quote");
            }
        }
    }

    private final int slot;
    private ItemStack template;
    private MatchMode matchMode = MatchMode.MATERIAL;
    private String displayName;
    private final List<String> lore = new ArrayList<>();
    private double unitPrice;
    private int bulkAmount;
    private double bulkPrice;
    private String permission = "";
    private TimedPercentage bonus = TimedPercentage.none();
    private final List<String> commands = new ArrayList<>();

    public BuyerOffer(int slot, ItemStack template, double unitPrice) {
        this.slot = slot;
        template(template);
        unitPrice(unitPrice);
        this.displayName = "&e" + humanize(this.template.getType());
    }

    private static String humanize(Material material) {
        String text = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public boolean matches(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || stack.getType() != template.getType()) return false;
        return matchMode == MatchMode.MATERIAL || stack.isSimilar(template);
    }

    public SaleQuote quote(SaleMode mode, int available) {
        if (available < 1) return null;
        return switch (mode) {
            case UNIT_ONE -> quote(1, unitPrice);
            case UNIT_ALL -> quote(available, multiply(unitPrice, available));
            case BULK_ONE -> bulkEnabled() && available >= bulkAmount ? quote(bulkAmount, bulkPrice) : null;
            case BULK_ALL -> {
                int lots = bulkEnabled() ? available / bulkAmount : 0;
                yield lots < 1 ? null : quote(lots * bulkAmount, multiply(bulkPrice, lots));
            }
        };
    }

    private static SaleQuote quote(int amount, double payout) { return new SaleQuote(amount, payout); }
    private static double multiply(double price, int count) {
        return BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(count)).doubleValue();
    }

    public int slot() { return slot; }
    public ItemStack template() { return template.clone(); }
    public void template(ItemStack template) {
        if (template == null || template.getType() == Material.AIR) {
            throw new IllegalArgumentException("Buyer item must be a real item.");
        }
        this.template = template.clone();
        this.template.setAmount(1);
    }
    public MatchMode matchMode() { return matchMode; }
    public void matchMode(MatchMode matchMode) { this.matchMode = matchMode; }
    public String displayName() { return displayName; }
    public void displayName(String displayName) { this.displayName = displayName; }
    public List<String> lore() { return lore; }
    public double unitPrice() { return unitPrice; }
    public void unitPrice(double unitPrice) {
        if (!Double.isFinite(unitPrice) || unitPrice <= 0) throw new IllegalArgumentException("Unit price must be positive.");
        this.unitPrice = unitPrice;
    }
    public int bulkAmount() { return bulkAmount; }
    public double bulkPrice() { return bulkPrice; }
    public boolean bulkEnabled() { return bulkAmount >= 2 && bulkPrice > 0; }
    public void bulk(int amount, double price) {
        if (amount <= 1) {
            bulkAmount = 0; bulkPrice = 0; return;
        }
        if (amount > 2304 || !Double.isFinite(price) || price <= 0) {
            throw new IllegalArgumentException("Bulk amount must be 2..2304 and price must be positive.");
        }
        bulkAmount = amount; bulkPrice = price;
    }
    public String permission() { return permission; }
    public void permission(String permission) { this.permission = permission == null ? "" : permission; }
    public List<String> commands() { return commands; }
    public TimedPercentage bonus() { return bonus; }
    public void bonus(TimedPercentage bonus) { this.bonus = bonus == null ? TimedPercentage.none() : bonus; }
}
