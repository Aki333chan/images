package dev.addons.npc.service;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.core.api.TransactionCategory;

/** Small synchronous WAL. Entries are tiny and every state transition is flushed before gameplay advances. */
public final class NpcSagaRepository {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, NpcSaga> entries = new LinkedHashMap<>();

    public NpcSagaRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "transactions.yml");
        load();
    }

    public synchronized void load() {
        entries.clear();
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("transactions");
        if (root == null) return;
        for (String rawId : root.getKeys(false)) {
            try {
                String path = "transactions." + rawId;
                UUID id = UUID.fromString(rawId);
                Map<String, String> metadata = new LinkedHashMap<>();
                ConfigurationSection metadataSection = yaml.getConfigurationSection(path + ".metadata");
                if (metadataSection != null) metadataSection.getKeys(false)
                        .forEach(key -> metadata.put(key, metadataSection.getString(key, "")));
                NpcSaga saga = new NpcSaga(id,
                        NpcSaga.Kind.valueOf(yaml.getString(path + ".kind", "SHOP_PURCHASE")),
                        NpcSaga.State.valueOf(yaml.getString(path + ".state", "HELD")),
                        UUID.fromString(yaml.getString(path + ".hold-id", "")),
                        yaml.getString(path + ".hold-key", ""),
                        UUID.fromString(yaml.getString(path + ".player", "")),
                        NpcSaga.account(yaml.getString(path + ".from.type", "PLAYER"),
                                yaml.getString(path + ".from.reference", "")),
                        NpcSaga.account(yaml.getString(path + ".to.type", "SERVER"),
                                yaml.getString(path + ".to.reference", "")),
                        yaml.getString(path + ".currency", "coins"),
                        new BigDecimal(yaml.getString(path + ".amount", "0")),
                        TransactionCategory.valueOf(yaml.getString(path + ".category", "NPC_PURCHASE")),
                        yaml.getString(path + ".reference", "unknown"),
                        yaml.getLong(path + ".created-at"), metadata);
                entries.put(id, saga);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Ignoring corrupt NPC transaction " + rawId, exception);
            }
        }
    }

    public synchronized NpcSaga begin(NpcSaga.Kind kind, UUID playerId,
                                      ovh.aurumgg.core.api.HoldSnapshot hold) {
        NpcSaga saga = NpcSaga.held(kind, playerId, hold);
        entries.put(saga.id(), saga);
        save();
        return saga;
    }

    public synchronized NpcSaga markApplied(NpcSaga saga) {
        NpcSaga applied = saga.state(NpcSaga.State.APPLIED);
        entries.put(applied.id(), applied);
        save();
        return applied;
    }

    public synchronized void complete(NpcSaga saga) {
        entries.remove(saga.id());
        save();
    }

    public synchronized List<NpcSaga> all() { return List.copyOf(new ArrayList<>(entries.values())); }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        for (NpcSaga saga : entries.values()) {
            String path = "transactions." + saga.id();
            yaml.set(path + ".kind", saga.kind().name());
            yaml.set(path + ".state", saga.state().name());
            yaml.set(path + ".hold-id", saga.holdId().toString());
            yaml.set(path + ".hold-key", saga.holdKey());
            yaml.set(path + ".player", saga.playerId().toString());
            yaml.set(path + ".from.type", saga.from().type().name());
            yaml.set(path + ".from.reference", saga.from().reference());
            yaml.set(path + ".to.type", saga.to().type().name());
            yaml.set(path + ".to.reference", saga.to().reference());
            yaml.set(path + ".currency", saga.currencyId());
            yaml.set(path + ".amount", saga.amount().toPlainString());
            yaml.set(path + ".category", saga.category().name());
            yaml.set(path + ".reference", saga.referenceId());
            yaml.set(path + ".created-at", saga.createdAt());
            saga.metadata().forEach((key, value) -> yaml.set(path + ".metadata." + key, value));
        }
        try { yaml.save(file); }
        catch (IOException exception) { throw new IllegalStateException("Could not persist NPC transaction journal", exception); }
    }
}
