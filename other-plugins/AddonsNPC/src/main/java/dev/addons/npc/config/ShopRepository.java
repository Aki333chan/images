package dev.addons.npc.config;

import dev.addons.npc.model.ShopDefinition;
import dev.addons.npc.model.ShopOffer;
import dev.addons.npc.model.TimedPercentage;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

public final class ShopRepository {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, ShopDefinition> shops = new LinkedHashMap<>();

    public ShopRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "shops.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("shops.yml", false);
        }
        shops.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int schemaVersion = yaml.getInt("schema-version", 1);
        ConfigurationSection root = yaml.getConfigurationSection("shops");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            try {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section != null) {
                    ShopDefinition shop = read(id, section, schemaVersion);
                    shops.put(shop.id(), shop);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not load shop '" + id + "'", exception);
            }
        }
        if (schemaVersion < 2) {
            plugin.getLogger().info("Migrating shops.yml from schema 1 to schema 2 (amount now means stock)");
            save();
        }
    }

    private ShopDefinition read(String id, ConfigurationSection section, int schemaVersion) {
        ShopDefinition shop = new ShopDefinition(id, section.getString("title", "&8Shop"), section.getInt("size", 27));
        shop.discount(readPercentage(section, "discount", 100));
        ConfigurationSection offers = section.getConfigurationSection("offers");
        if (offers == null) {
            return shop;
        }
        for (String rawSlot : offers.getKeys(false)) {
            int slot = Integer.parseInt(rawSlot);
            ConfigurationSection offerSection = offers.getConfigurationSection(rawSlot);
            if (offerSection == null || slot < 0 || slot >= shop.size()) {
                continue;
            }
            Material item = requireMaterial(offerSection.getString("item", "STONE"));
            int configuredAmount = offerSection.getInt("amount", -1);
            boolean legacy = schemaVersion < 2;
            boolean unlimited = legacy || (offerSection.contains("infinite")
                    ? offerSection.getBoolean("infinite") : configuredAmount <= 0);
            int stock = unlimited ? -1 : Math.max(0, configuredAmount);
            ShopOffer offer = new ShopOffer(slot, item, stock, offerSection.getDouble("price"));
            ItemStack product = offerSection.getItemStack("product");
            if (product != null && !product.getType().isAir()) offer.product(product);
            else if (offerSection.contains("potion-type")) {
                ItemStack potion = new ItemStack(item);
                if (!(potion.getItemMeta() instanceof PotionMeta meta)) {
                    throw new IllegalArgumentException("potion-type is only valid for potion items");
                }
                meta.setBasePotionType(PotionType.valueOf(
                        offerSection.getString("potion-type", "WATER").toUpperCase(java.util.Locale.ROOT)));
                potion.setItemMeta(meta);
                offer.product(potion);
            }
            int quantity = legacy ? Math.max(1, configuredAmount) : offerSection.getInt("quantity", 1);
            offer.quantity(Math.min(item.getMaxStackSize(), quantity));
            offer.icon(requireMaterial(offerSection.getString("icon", item.name())));
            offer.displayName(offerSection.getString("display-name", offer.displayName()));
            offer.lore().addAll(offerSection.getStringList("lore"));
            offer.permission(offerSection.getString("permission", ""));
            offer.commands().addAll(offerSection.getStringList("commands"));
            offer.discount(readPercentage(offerSection, "discount", 100));
            shop.offers().put(slot, offer);
        }
        return shop;
    }

    private static Material requireMaterial(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem()) {
            throw new IllegalArgumentException("Unknown item material: " + name);
        }
        return material;
    }

    private static TimedPercentage readPercentage(ConfigurationSection section, String path, double maximum) {
        double percent = section.getDouble(path + ".percent", 0);
        if (percent < 0 || percent > maximum) throw new IllegalArgumentException("Invalid discount percentage: " + percent);
        return new TimedPercentage(percent, section.getLong(path + ".expires-at", 0));
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 4);
        for (ShopDefinition shop : shops.values()) {
            String path = "shops." + shop.id();
            yaml.set(path + ".title", shop.title());
            yaml.set(path + ".size", shop.size());
            writePercentage(yaml, path + ".discount", shop.discount());
            for (ShopOffer offer : shop.offers().values()) {
                String offerPath = path + ".offers." + offer.slot();
                yaml.set(offerPath + ".icon", offer.icon().name());
                yaml.set(offerPath + ".display-name", offer.displayName());
                yaml.set(offerPath + ".lore", offer.lore());
                yaml.set(offerPath + ".price", offer.price());
                yaml.set(offerPath + ".item", offer.item().name());
                ItemStack product = offer.productTemplate();
                yaml.set(offerPath + ".product", product);
                if (product != null && product.getItemMeta() instanceof PotionMeta potion && potion.hasBasePotionType()) {
                    yaml.set(offerPath + ".potion-type", potion.getBasePotionType().name());
                }
                yaml.set(offerPath + ".amount", offer.unlimited() ? -1 : offer.stock());
                yaml.set(offerPath + ".infinite", offer.unlimited());
                yaml.set(offerPath + ".quantity", offer.quantity());
                yaml.set(offerPath + ".permission", offer.permission());
                yaml.set(offerPath + ".commands", offer.commands());
                writePercentage(yaml, offerPath + ".discount", offer.discount());
            }
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save shops.yml", exception);
        }
    }

    private static void writePercentage(YamlConfiguration yaml, String path, TimedPercentage percentage) {
        yaml.set(path + ".percent", percentage.percent());
        yaml.set(path + ".expires-at", percentage.expiresAtMillis());
    }

    public ShopDefinition get(String id) { return shops.get(id.toLowerCase()); }
    public void put(ShopDefinition shop) { shops.put(shop.id(), shop); }
    public ShopDefinition remove(String id) { return shops.remove(id.toLowerCase()); }
    public Collection<ShopDefinition> all() { return shops.values(); }
    public Collection<String> ids() { return shops.keySet(); }
}
