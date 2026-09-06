package dev.addons.npc.config;

import dev.addons.npc.model.ActionDefinition;
import dev.addons.npc.model.ClickMode;
import dev.addons.npc.model.DialogueMode;
import dev.addons.npc.model.NpcDefinition;
import dev.addons.npc.model.LookMode;
import dev.addons.npc.model.SkinSpec;
import dev.addons.npc.model.StoredLocation;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Pose;
import org.bukkit.plugin.java.JavaPlugin;

public final class NpcRepository {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, NpcDefinition> definitions = new LinkedHashMap<>();

    public NpcRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "npcs.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("npcs.yml", false);
        }
        definitions.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("npcs");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            try {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section != null) {
                    NpcDefinition definition = read(id, section);
                    definitions.put(definition.id(), definition);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not load NPC '" + id + "'", exception);
            }
        }
    }

    private NpcDefinition read(String id, ConfigurationSection section) {
        StoredLocation location = new StoredLocation(
                section.getString("location.world", "world"),
                section.getDouble("location.x"),
                section.getDouble("location.y"),
                section.getDouble("location.z"),
                (float) section.getDouble("location.yaw"),
                (float) section.getDouble("location.pitch"));
        NpcDefinition npc = new NpcDefinition(id, location, section.getString("name", "&e" + id));
        npc.description(section.getString("description", ""));
        npc.enabled(section.getBoolean("enabled", true));
        npc.entityType(EntityType.valueOf(section.getString("entity-type", "MANNEQUIN").toUpperCase()));
        npc.clickMode(ClickMode.parse(section.getString("click-mode", "RIGHT")));
        npc.dialogueMode(DialogueMode.parse(section.getString("dialogue.mode", "SEQUENTIAL")));
        npc.messages().addAll(section.getStringList("dialogue.lines"));
        npc.cooldownSeconds(section.getDouble("cooldown-seconds", 1.0));
        npc.permission(section.getString("permission", ""));
        npc.lookAtPlayers(section.getBoolean("look-at.enabled", true));
        npc.lookRange(section.getDouble("look-at.range", 8.0));
        npc.lookMode(LookMode.parse(section.getString("look-at.mode",
                plugin.getConfig().getString("settings.default-look-mode", "HEAD"))));
        npc.visibilityRange(section.getDouble("visibility.entity-range",
                plugin.getConfig().getDouble("settings.default-visibility-range", 48.0)));
        npc.nameVisibilityRange(section.getDouble("visibility.name-range",
                plugin.getConfig().getDouble("settings.default-name-visibility-range", 24.0)));
        npc.pose(Pose.valueOf(section.getString("pose", "STANDING").toUpperCase()));
        npc.rightHand(readItem(section, "equipment.right-hand"));
        npc.leftHand(readItem(section, "equipment.left-hand"));

        String skinType = section.getString("skin.type", "NONE");
        String skinValue = section.getString("skin.value", "");
        npc.skin(new SkinSpec(SkinSpec.Type.valueOf(skinType.toUpperCase()), skinValue));
        for (String serialized : section.getStringList("actions")) {
            try {
                npc.actions().add(ActionDefinition.parse(serialized));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Ignoring invalid action on NPC '" + id + "': " + serialized);
            }
        }
        return npc;
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (NpcDefinition npc : definitions.values()) {
            String path = "npcs." + npc.id();
            yaml.set(path + ".name", npc.name());
            yaml.set(path + ".description", npc.description());
            yaml.set(path + ".enabled", npc.enabled());
            yaml.set(path + ".entity-type", npc.entityType().name());
            yaml.set(path + ".location.world", npc.location().world());
            yaml.set(path + ".location.x", npc.location().x());
            yaml.set(path + ".location.y", npc.location().y());
            yaml.set(path + ".location.z", npc.location().z());
            yaml.set(path + ".location.yaw", npc.location().yaw());
            yaml.set(path + ".location.pitch", npc.location().pitch());
            yaml.set(path + ".skin.type", npc.skin().type().name());
            yaml.set(path + ".skin.value", npc.skin().value());
            yaml.set(path + ".click-mode", npc.clickMode().name());
            yaml.set(path + ".dialogue.mode", npc.dialogueMode().name());
            yaml.set(path + ".dialogue.lines", npc.messages());
            yaml.set(path + ".actions", npc.actions().stream().map(ActionDefinition::serialize).toList());
            yaml.set(path + ".cooldown-seconds", npc.cooldownSeconds());
            yaml.set(path + ".permission", npc.permission());
            yaml.set(path + ".look-at.enabled", npc.lookAtPlayers());
            yaml.set(path + ".look-at.range", npc.lookRange());
            yaml.set(path + ".look-at.mode", npc.lookMode().name());
            yaml.set(path + ".visibility.entity-range", npc.visibilityRange());
            yaml.set(path + ".visibility.name-range", npc.nameVisibilityRange());
            yaml.set(path + ".pose", npc.pose().name());
            yaml.set(path + ".equipment.right-hand", npc.rightHand());
            yaml.set(path + ".equipment.left-hand", npc.leftHand());
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save npcs.yml", exception);
        }
    }

    private static org.bukkit.inventory.ItemStack readItem(ConfigurationSection section, String path) {
        org.bukkit.inventory.ItemStack item = section.getItemStack(path);
        if (item != null) return item;
        String shorthand = section.getString(path, "");
        Material material = Material.matchMaterial(shorthand);
        return material == null || !material.isItem() || material.isAir()
                ? null : new org.bukkit.inventory.ItemStack(material);
    }

    public NpcDefinition get(String id) {
        return definitions.get(id.toLowerCase());
    }

    public void put(NpcDefinition definition) {
        definitions.put(definition.id(), definition);
    }

    public NpcDefinition remove(String id) {
        return definitions.remove(id.toLowerCase());
    }

    public Collection<NpcDefinition> all() {
        return definitions.values();
    }

    public Collection<String> ids() {
        return definitions.keySet();
    }
}
