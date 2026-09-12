package ovh.aurumgg.core.api;

import java.util.Map;
import java.util.Objects;

/** Canonical, transport-safe representation of one current rule revision. */
public record RuleResource(RuleType type, String id, long revision, Map<String, String> fields) {
    public RuleResource {
        Objects.requireNonNull(type, "type");
        id = Objects.requireNonNull(id, "id").trim();
        fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
        if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}") || revision < 0
                || fields.size() > 48 || fields.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null || entry.getKey().isBlank()
                        || entry.getKey().length() > 96 || entry.getValue().length() > 512
                        || hasControl(entry.getKey()) || hasControl(entry.getValue()))) {
            throw new IllegalArgumentException("Invalid rule resource");
        }
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
