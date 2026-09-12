package ovh.aurumgg.core.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One bounded audit response. */
public record EconomyAuditPage(EconomyAuditSection section, String currencyId,
                               Map<String, String> summary,
                               List<EconomyAuditRecord> records, Instant generatedAt) {
    public EconomyAuditPage {
        Objects.requireNonNull(section, "section");
        currencyId = Objects.requireNonNull(currencyId, "currencyId");
        summary = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(summary, "summary")));
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        Objects.requireNonNull(generatedAt, "generatedAt");
        if (currencyId.isBlank() || currencyId.length() > 32 || summary.size() > 32 || records.size() > 200
                || summary.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null || entry.getKey().isBlank()
                || entry.getKey().length() > 48 || entry.getValue().length() > 2_048)) {
            throw new IllegalArgumentException("Economy audit page is too large");
        }
    }
}
