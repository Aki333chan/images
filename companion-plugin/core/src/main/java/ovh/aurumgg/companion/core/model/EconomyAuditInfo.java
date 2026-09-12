package ovh.aurumgg.companion.core.model;

import java.util.List;
import java.util.Map;

/** One bounded, read-only section supplied by AurumCore. */
public record EconomyAuditInfo(String section, String currency, long generatedAt,
                               Map<String, String> summary, List<Record> records) {
    public EconomyAuditInfo {
        summary = Map.copyOf(summary);
        records = List.copyOf(records);
    }

    public record Record(String type, Map<String, String> fields) {
        public Record { fields = Map.copyOf(fields); }
    }
}
