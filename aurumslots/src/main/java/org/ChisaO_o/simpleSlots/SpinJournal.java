package org.ChisaO_o.simpleSlots;

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

final class SpinJournal {
    private final SimpleSlots plugin;
    private final File file;
    private final Map<UUID, SpinRecord> entries = new LinkedHashMap<>();

    SpinJournal(SimpleSlots plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "transactions.yml");
        load();
    }

    synchronized SpinRecord begin(UUID operationId, UUID playerId, String machineId,
                                  ovh.aurumgg.core.api.HoldSnapshot hold) {
        SpinRecord record = SpinRecord.accepted(operationId, playerId, machineId, hold);
        entries.put(operationId, record); save(); return record;
    }

    synchronized SpinRecord payout(SpinRecord record, BigDecimal amount) {
        SpinRecord updated = record.payout(amount);
        entries.put(updated.operationId(), updated); save(); return updated;
    }

    synchronized void complete(SpinRecord record) { entries.remove(record.operationId()); save(); }
    synchronized List<SpinRecord> all() { return List.copyOf(new ArrayList<>(entries.values())); }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("transactions");
        if (root == null) return;
        for (String id : root.getKeys(false)) try {
            String path = "transactions." + id;
            SpinRecord record = new SpinRecord(UUID.fromString(id),
                    UUID.fromString(yaml.getString(path + ".hold-id", "")),
                    yaml.getString(path + ".hold-key", ""),
                    UUID.fromString(yaml.getString(path + ".player", "")),
                    yaml.getString(path + ".machine", ""), yaml.getString(path + ".currency", "coins"),
                    new BigDecimal(yaml.getString(path + ".bet", "0")),
                    new BigDecimal(yaml.getString(path + ".reserved-debit",
                            yaml.getString(path + ".bet", "0"))),
                    SpinRecord.State.valueOf(yaml.getString(path + ".state", "ACCEPTED")),
                    new BigDecimal(yaml.getString(path + ".payout", "0")), yaml.getLong(path + ".created-at"));
            entries.put(record.operationId(), record);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.SEVERE, "Ignoring corrupt slot transaction " + id, error);
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration(); yaml.set("schema-version", 1);
        for (SpinRecord record : entries.values()) {
            String path = "transactions." + record.operationId();
            yaml.set(path + ".hold-id", record.holdId().toString()); yaml.set(path + ".hold-key", record.holdKey());
            yaml.set(path + ".player", record.playerId().toString()); yaml.set(path + ".machine", record.machineId());
            yaml.set(path + ".currency", record.currencyId()); yaml.set(path + ".bet", record.bet().toPlainString());
            yaml.set(path + ".reserved-debit", record.reservedDebit().toPlainString());
            yaml.set(path + ".state", record.state().name()); yaml.set(path + ".payout", record.payout().toPlainString());
            yaml.set(path + ".created-at", record.createdAt());
        }
        try { yaml.save(file); }
        catch (IOException error) { throw new IllegalStateException("Could not persist slot transaction journal", error); }
    }
}
