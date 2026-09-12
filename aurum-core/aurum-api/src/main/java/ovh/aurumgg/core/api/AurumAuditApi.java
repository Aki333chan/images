package ovh.aurumgg.core.api;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Bounded, read-only audit projection for trusted administration bridges. */
public interface AurumAuditApi {
    /**
     * Read one independently bounded section. Implementations must do database
     * work off the Paper thread and must never turn an unavailable store into
     * an authoritative empty result.
     */
    CompletionStage<Optional<EconomyAuditPage>> read(
            EconomyAuditSection section, String currencyId, String accountKey, int limit);

    default CompletionStage<Optional<EconomyAuditPage>> read(
            EconomyAuditSection section, String currencyId, int limit) {
        return read(section, currencyId, "", limit);
    }
}
