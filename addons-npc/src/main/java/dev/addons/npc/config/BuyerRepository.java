package dev.addons.npc.config;

import dev.addons.npc.model.BuyerDefinition;
import dev.addons.npc.model.BuyerOffer;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class BuyerRepository {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, BuyerDefinition> buyers = new LinkedHashMap<>();

    public BuyerRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "buyers.yml");
    }

    public void load() {
        if (!file.exists()) plugin.saveResource("buyers.yml", false);
        buyers.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("buyers");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            try {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section != null) {
                    BuyerDefinition buyer = read(id, section);
                    buyers.put(buyer.id(), buyer);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not load buyer '" + id + "'", exception);
            }
        }
    }

    private BuyerDefinition read(String id, ConfigurationSection section) {
        BuyerDefinition buyer = new BuyerDefinition(id, section.getString("title", "&8Buyer"), section.getInt("size", 27));
        buyer.bonus(readPercentage(section, "bonus"));
        ConfigurationSection offers = section.getConfigurationSection("offers");
        if (offers == null) return buyer;
        for (String rawSlot : offers.getKeys(false)) {
            try {
                int slot = Integer.parseInt(rawSlot);
                ConfigurationSection offerSection = offers.getConfigurationSection(rawSlot);
                if (offerSection == null || slot < 0 || slot >= buyer.size()) continue;
                ItemStack template = offerSection.getItemStack("template");
                if (template == null) template = new ItemStack(requireMaterial(offerSection.getString("item", "STONE")));
                BuyerOffer offer = new BuyerOffer(slot, template, offerSection.getDouble("unit-price"));
                offer.matchMode(BuyerOffer.MatchMode.parse(offerSection.getString("match", "material")));
                int bulkAmount = offerSection.getInt("bulk-amount", 0);
                if (bulkAmount > 1) offer.bulk(bulkAmount, offerSection.getDouble("bulk-price"));
                offer.displayName(offerSection.getString("display-name", offer.displayName()));
                offer.lore().addAll(offerSection.getStringList("lore"));
                offer.permission(offerSection.getString("permission", ""));
                offer.commands().addAll(offerSection.getStringList("commands"));
                offer.bonus(readPercentage(offerSection, "bonus"));
                buyer.offers().put(slot, offer);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Ignoring invalid buyer offer '" + id + ":" + rawSlot + "'", exception);
            }
        }
        return buyer;
    }

    private static Material requireMaterial(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("Unknown item material: " + name);
        }
        return material;
    }

    private static TimedPercentage readPercentage(ConfigurationSection section, String path) {
        double percent = section.getDouble(path + ".percent", 0);
        if (percent < 0 || percent > 1000) throw new IllegalArgumentException("Invalid bonus percentage: " + percent);
        return new TimedPercentage(percent, section.getLong(path + ".expires-at", 0));
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 2);
        for (BuyerDefinition buyer : buyers.values()) {
            String path = "buyers." + buyer.id();
            yaml.set(path + ".title", buyer.title());
            yaml.set(path + ".size", buyer.size());
            writePercentage(yaml, path + ".bonus", buyer.bonus());
            for (BuyerOffer offer : buyer.offers().values()) {
                String offerPath = path + ".offers." + offer.slot();
                yaml.set(offerPath + ".item", offer.template().getType().name());
                yaml.set(offerPath + ".template", offer.matchMode() == BuyerOffer.MatchMode.EXACT ? offer.template() : null);
                yaml.set(offerPath + ".match", offer.matchMode().name());
                yaml.set(offerPath + ".display-name", offer.displayName());
                yaml.set(offerPath + ".lore", offer.lore());
                yaml.set(offerPath + ".unit-price", offer.unitPrice());
                yaml.set(offerPath + ".bulk-amount", offer.bulkEnabled() ? offer.bulkAmount() : 0);
                yaml.set(offerPath + ".bulk-price", offer.bulkEnabled() ? offer.bulkPrice() : 0);
                yaml.set(offerPath + ".permission", offer.permission());
                yaml.set(offerPath + ".commands", offer.commands());
                writePercentage(yaml, offerPath + ".bonus", offer.bonus());
            }
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save buyers.yml", exception);
        }
    }

    private static void writePercentage(YamlConfiguration yaml, String path, TimedPercentage percentage) {
        yaml.set(path + ".percent", percentage.percent());
        yaml.set(path + ".expires-at", percentage.expiresAtMillis());
    }

    public BuyerDefinition get(String id) { return buyers.get(id.toLowerCase()); }
    public void put(BuyerDefinition buyer) { buyers.put(buyer.id(), buyer); }
    public BuyerDefinition remove(String id) { return buyers.remove(id.toLowerCase()); }
    public Collection<BuyerDefinition> all() { return buyers.values(); }
    public Collection<String> ids() { return buyers.keySet(); }
}
