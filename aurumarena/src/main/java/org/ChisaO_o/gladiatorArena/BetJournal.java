package org.ChisaO_o.gladiatorArena;

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

/**
 * Журнал незавершённых списаний: {@code plugins/AurumArena/transactions.yml}.
 *
 * <h2>Что он гарантирует</h2>
 *
 * Билет появляется в файле ДО того, как деньги реально уходят со счёта игрока,
 * и исчезает СРАЗУ после того, как ставка попала в {@code recovery.yml}.
 * Поэтому запись, пережившая перезапуск, означает ровно одно: списание начато,
 * а до учёта ставки дело не дошло. Такие деньги надо вернуть игроку —
 * подробности в {@link ArenaEconomyService#recover()}.
 *
 * <h2>Почему YAML, а не база</h2>
 *
 * У арены уже есть {@code recovery.yml} в том же формате, а записей здесь
 * единицы: билет живёт доли секунды и только в момент клика. Заводить ради
 * этого таблицу значило бы добавить точку отказа туда, где её нет.
 */
final class BetJournal {

    private final GladiatorArena plugin;
    private final File file;
    private final Map<UUID, BetTicket> tickets = new LinkedHashMap<>();

    BetJournal(GladiatorArena plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "transactions.yml");
        load();
    }

    synchronized BetTicket open(BetTicket ticket) {
        tickets.put(ticket.operation(), ticket);
        save();
        return ticket;
    }

    synchronized void close(BetTicket ticket) {
        if (tickets.remove(ticket.operation()) != null) save();
    }

    synchronized List<BetTicket> all() {
        return List.copyOf(new ArrayList<>(tickets.values()));
    }

    synchronized boolean isEmpty() {
        return tickets.isEmpty();
    }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = data.getConfigurationSection("tickets");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(key);
            if (entry == null) continue;
            try {
                BetTicket ticket = new BetTicket(
                        UUID.fromString(key),
                        UUID.fromString(entry.getString("player", "")),
                        entry.getString("arena", ""),
                        BetTicket.Purpose.valueOf(entry.getString("purpose", "BET")),
                        UUID.fromString(entry.getString("hold-id", "")),
                        entry.getString("hold-key", ""),
                        new BigDecimal(entry.getString("amount", "0")),
                        new BigDecimal(entry.getString("reserved", entry.getString("amount", "0"))),
                        entry.getString("currency", ""));
                tickets.put(ticket.operation(), ticket);
            } catch (IllegalArgumentException | NullPointerException error) {
                // Битую запись пропускаем, но НЕ удаляем файл: остальные билеты
                // в нём — чьи-то настоящие деньги.
                plugin.getLogger().log(Level.WARNING, "Пропущен нечитаемый билет " + key, error);
            }
        }
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (BetTicket ticket : tickets.values()) {
            String base = "tickets." + ticket.operation();
            data.set(base + ".player", ticket.playerId().toString());
            data.set(base + ".arena", ticket.arena());
            data.set(base + ".purpose", ticket.purpose().name());
            data.set(base + ".hold-id", ticket.holdId().toString());
            data.set(base + ".hold-key", ticket.holdKey());
            // Строкой, а не double: сумма денежная, и округление при обратном
            // чтении здесь недопустимо.
            data.set(base + ".amount", ticket.amount().toPlainString());
            data.set(base + ".reserved", ticket.reservedDebit().toPlainString());
            data.set(base + ".currency", ticket.currencyId());
        }
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            data.save(file);
        } catch (IOException error) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить журнал списаний арены", error);
        }
    }
}
