package dev.addons.npc.model;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class ShopOffer {
    private final int slot;
    private Material icon;
    private String displayName;
    private final List<String> lore = new ArrayList<>();
    private double price;
    private Material item;
    private ItemStack productTemplate;
    private int quantity = 1;
    private int stock;
    private String permission = "";
    private TimedPercentage discount = TimedPercentage.none();
    private final List<String> commands = new ArrayList<>();

    public ShopOffer(int slot, Material item, int stock, double price) {
        this.slot = slot;
        this.item = item;
        this.icon = item;
        this.stock = stock;
        this.price = Math.max(0, price);
        this.displayName = "&e" + humanize(item);
    }

    private static String humanize(Material material) {
        String text = material.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public int slot() { return slot; }
    public Material icon() { return icon; }
    public void icon(Material icon) { this.icon = icon; }
    public String displayName() { return displayName; }
    public void displayName(String displayName) { this.displayName = displayName; }
    public List<String> lore() { return lore; }
    public double price() { return price; }
    public void price(double price) { this.price = Math.max(0, price); }
    public Material item() { return item; }
    public void item(Material item) {
        this.item = item;
        this.productTemplate = null;
        quantity(quantity);
    }
    public ItemStack product() {
        ItemStack result = productTemplate == null ? new ItemStack(item) : productTemplate.clone();
        result.setAmount(quantity);
        return result;
    }
    public void product(ItemStack product) {
        if (product == null || product.getType().isAir()) throw new IllegalArgumentException("Product cannot be empty.");
        this.item = product.getType();
        this.productTemplate = product.clone();
        this.productTemplate.setAmount(1);
        quantity(quantity);
    }
    public ItemStack productTemplate() { return productTemplate == null ? null : productTemplate.clone(); }
    public int quantity() { return quantity; }
    public void quantity(int quantity) {
        this.quantity = Math.max(1, quantity);
    }
    public int stock() { return stock; }
    public void stock(int stock) { this.stock = stock; }
    public boolean unlimited() { return stock < 0; }
    public boolean available() { return unlimited() || stock >= quantity; }
    public void consume() {
        if (!unlimited()) stock = Math.max(0, stock - quantity);
    }
    public String permission() { return permission; }
    public void permission(String permission) { this.permission = permission == null ? "" : permission; }
    public List<String> commands() { return commands; }
    public TimedPercentage discount() { return discount; }
    public void discount(TimedPercentage discount) { this.discount = discount == null ? TimedPercentage.none() : discount; }
}
