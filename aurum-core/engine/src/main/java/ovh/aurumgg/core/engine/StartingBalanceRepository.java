package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.UUID;

/** Blocking persistence, called only on the database executor. */
public interface StartingBalanceRepository {
    StartingBalanceSettings current() throws Exception;
    long installedAt() throws Exception;
    StartingBalanceSettings save(StartingBalanceSettings settings, long expected, String actor, String reason) throws Exception;
    Decision observe(UUID player, boolean newcomer) throws Exception;
    void finish(UUID player) throws Exception;

    record Decision(UUID player, String currency, BigDecimal amount, long revision, String status) {
        public boolean pending() { return status.equals("PENDING"); }
    }
}
