package ovh.aurumgg.core.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A stable record kind plus bounded display fields. */
public record EconomyAuditRecord(String type, Map<String, String> fields) {
    public EconomyAuditRecord {
        type = Objects.requireNonNull(type, "type");
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(fields, "fields")));
        if (type.isBlank() || type.length() > 48 || fields.size() > 32
                || fields.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null || entry.getKey().isBlank()
                || entry.getKey().length() > 48 || entry.getValue().length() > 2_048)) {
            throw new IllegalArgumentException("Invalid economy audit record");
        }
    }
}
