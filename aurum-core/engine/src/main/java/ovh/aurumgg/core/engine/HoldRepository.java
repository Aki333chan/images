package ovh.aurumgg.core.engine;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.HoldSnapshot;

public interface HoldRepository {
    HoldResult reserve(HoldSnapshot hold) throws SQLException;
    Optional<HoldSnapshot> find(UUID id, CurrencySpec currency) throws SQLException;
    Optional<HoldSnapshot> find(String idempotencyKey, CurrencySpec currency) throws SQLException;
    HoldResult resolve(UUID id, HoldSnapshot.Status status, CurrencySpec currency) throws SQLException;
}
