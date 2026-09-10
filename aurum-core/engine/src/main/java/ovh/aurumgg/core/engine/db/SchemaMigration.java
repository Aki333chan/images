package ovh.aurumgg.core.engine.db;

import java.util.List;

public record SchemaMigration(int version, String description, String checksum, List<String> statements) {
    public SchemaMigration {
        statements = List.copyOf(statements);
        if (version <= 0 || description.isBlank() || checksum.isBlank() || statements.isEmpty()) {
            throw new IllegalArgumentException("Invalid schema migration");
        }
    }
}
