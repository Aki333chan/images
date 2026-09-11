package dev.addons.npc.config;

import dev.addons.npc.model.ExchangeOffer;
import dev.addons.npc.model.ExchangerDefinition;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExchangerRepository {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, ExchangerDefinition> exchangers = new LinkedHashMap<>();

    public ExchangerRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "exchangers.yml");
    }

    public void load() {
        if (!file.exists()) plugin.saveResource("exchangers.yml", false);
        exchangers.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("exchangers");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            try {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section != null) {
                    ExchangerDefinition exchanger = read(id, section);
                    exchangers.put(exchanger.id(), exchanger);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not load exchanger '" + id + "'", exception);
            }
        }
    }

    private ExchangerDefinition read(String id, ConfigurationSection section) {
        ExchangerDefinition exchanger = new ExchangerDefinition(id,
                section.getString("title", "&8Currency exchange"), section.getInt("size", 27));
        ConfigurationSection offers = section.getConfigurationSection("offers");
        if (offers == null) return exchanger;
        for (String rawSlot : offers.getKeys(false)) {
            try {
                int slot = Integer.parseInt(rawSlot);
                ConfigurationSection value = offers.getConfigurationSection(rawSlot);
                if (value == null || slot < 0 || slot >= exchanger.size()) continue;
                ExchangeOffer offer = new ExchangeOffer(slot, value.getString("from", ""),
                        value.getString("to", ""), new BigDecimal(value.getString("amount", "0")));
                Material icon = Material.matchMaterial(value.getString("icon", "EMERALD"));
                offer.icon(icon);
                offer.displayName(value.getString("display-name", "&aCurrency exchange"));
                offer.permission(value.getString("permission", ""));
                offer.lore().addAll(value.getStringList("lore"));
                exchanger.offers().put(slot, offer);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "Ignoring invalid exchange offer '" + id + ":" + rawSlot + "'", exception);
            }
        }
        return exchanger;
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        for (ExchangerDefinition exchanger : exchangers.values()) {
            String path = "exchangers." + exchanger.id();
            yaml.set(path + ".title", exchanger.title());
            yaml.set(path + ".size", exchanger.size());
            for (ExchangeOffer offer : exchanger.offers().values()) {
                String item = path + ".offers." + offer.slot();
                yaml.set(item + ".from", offer.fromCurrency());
                yaml.set(item + ".to", offer.toCurrency());
                yaml.set(item + ".amount", offer.sourceAmount().toPlainString());
                yaml.set(item + ".icon", offer.icon().name());
                yaml.set(item + ".display-name", offer.displayName());
                yaml.set(item + ".lore", offer.lore());
                yaml.set(item + ".permission", offer.permission());
            }
        }
        try { yaml.save(file); }
        catch (IOException exception) { throw new IllegalStateException("Could not save exchangers.yml", exception); }
    }

    public ExchangerDefinition get(String id) {
        return id == null ? null : exchangers.get(id.toLowerCase(Locale.ROOT));
    }
    public void put(ExchangerDefinition value) { exchangers.put(value.id(), value); }
    public ExchangerDefinition remove(String id) {
        return id == null ? null : exchangers.remove(id.toLowerCase(Locale.ROOT));
    }
    public Collection<ExchangerDefinition> all() { return exchangers.values(); }
    public Collection<String> ids() { return exchangers.keySet(); }
}
