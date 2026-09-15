package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.*;
final class MemoryStartingBalanceRepository implements StartingBalanceRepository {
    StartingBalanceSettings settings = new StartingBalanceSettings(1, true, "coins", new BigDecimal("100"));
    final Map<UUID, Decision> players = new HashMap<>();
    String actor, reason;
    boolean failFinish;
    public StartingBalanceSettings current() { return settings; }
    public long installedAt() { return 1000; }
    public synchronized StartingBalanceSettings save(StartingBalanceSettings value, long expected, String actor, String reason) throws Exception {
        if (expected != settings.revision()) throw new StaleRuleRevisionException(expected, settings.revision());
        this.actor = actor; this.reason = reason;
        return settings = new StartingBalanceSettings(expected + 1, value.enabled(), value.currencyId(), value.amount());
    }
    public synchronized Decision observe(UUID player, boolean newcomer) {
        return players.computeIfAbsent(player, id -> new Decision(id, settings.currencyId(), settings.amount(),
                settings.revision(), newcomer && settings.grants() ? "PENDING" : "SKIPPED"));
    }
    public synchronized void finish(UUID player) throws Exception {
        if (failFinish) { failFinish = false; throw new java.sql.SQLException("lost completion"); }
        Decision d = players.get(player);
        players.put(player, new Decision(player, d.currency(), d.amount(), d.revision(), "PAID"));
    }
}
