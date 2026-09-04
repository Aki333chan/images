package dev.addons.npc.config;

import dev.addons.npc.model.GuildBonusOffer;
import dev.addons.npc.model.GuildBonusType;
import dev.addons.npc.model.GuildRankRequirement;
import dev.addons.npc.model.GuildTraderDefinition;
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

public final class GuildTraderRepository {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, GuildTraderDefinition> traders = new LinkedHashMap<>();

    public GuildTraderRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "guild-traders.yml");
    }

    public void load() {
        if (!file.exists()) plugin.saveResource("guild-traders.yml", false);
        traders.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("guild-traders");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            try {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section != null) {
                    GuildTraderDefinition trader = read(id, section);
                    traders.put(trader.id(), trader);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not load guild trader '" + id + "'", exception);
            }
        }
    }

    private GuildTraderDefinition read(String id, ConfigurationSection section) {
        GuildTraderDefinition trader = new GuildTraderDefinition(id,
                section.getString("title", "&8Гильдейские усиления"), section.getInt("size", 27));
        trader.requiredRank(GuildRankRequirement.parse(section.getString("required-rank", "OFFICER")));
        ConfigurationSection offers = section.getConfigurationSection("offers");
        if (offers == null) return trader;
        for (String rawSlot : offers.getKeys(false)) {
            int slot = Integer.parseInt(rawSlot);
            ConfigurationSection offerSection = offers.getConfigurationSection(rawSlot);
            if (offerSection == null || slot < 0 || slot >= trader.size()) continue;
            GuildBonusType type = GuildBonusType.parse(offerSection.getString("type"));
            GuildBonusOffer offer = new GuildBonusOffer(slot, type,
                    offerSection.getDouble("magnitude", 1.0),
                    Math.max(0, offerSection.getLong("duration-seconds", 0)),
                    offerSection.getDouble("price", 0));
            Material icon = Material.matchMaterial(offerSection.getString("icon", type.defaultIcon().name()));
            if (icon == null || !icon.isItem()) throw new IllegalArgumentException("Unknown icon material in slot " + slot);
            offer.icon(icon);
            offer.displayName(offerSection.getString("display-name", offer.displayName()));
            offer.permission(offerSection.getString("permission", ""));
            offer.lore().addAll(offerSection.getStringList("lore"));
            trader.offers().put(slot, offer);
        }
        return trader;
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        for (GuildTraderDefinition trader : traders.values()) {
            String path = "guild-traders." + trader.id();
            yaml.set(path + ".title", trader.title());
            yaml.set(path + ".size", trader.size());
            yaml.set(path + ".required-rank", trader.requiredRank().name());
            for (GuildBonusOffer offer : trader.offers().values()) {
                String offerPath = path + ".offers." + offer.slot();
                yaml.set(offerPath + ".type", offer.type().name());
                yaml.set(offerPath + ".magnitude", offer.magnitude());
                yaml.set(offerPath + ".duration-seconds", offer.durationSeconds());
                yaml.set(offerPath + ".price", offer.price());
                yaml.set(offerPath + ".icon", offer.icon().name());
                yaml.set(offerPath + ".display-name", offer.displayName());
                yaml.set(offerPath + ".lore", offer.lore());
                yaml.set(offerPath + ".permission", offer.permission());
            }
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save guild-traders.yml", exception);
        }
    }

    public GuildTraderDefinition get(String id) { return id == null ? null : traders.get(id.toLowerCase()); }
    public void put(GuildTraderDefinition trader) { traders.put(trader.id(), trader); }
    public GuildTraderDefinition remove(String id) { return id == null ? null : traders.remove(id.toLowerCase()); }
    public Collection<GuildTraderDefinition> all() { return traders.values(); }
    public Collection<String> ids() { return traders.keySet(); }
}
