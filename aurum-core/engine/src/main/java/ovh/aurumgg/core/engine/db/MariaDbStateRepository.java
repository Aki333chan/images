package ovh.aurumgg.core.engine.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;

public final class MariaDbStateRepository {
    private final DataSource dataSource;

    public MariaDbStateRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<String> get(String key) throws SQLException {
        validate(key, 64, "key");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state_value FROM aurum_runtime_state WHERE state_key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        }
    }

    public void put(String key, String value) throws SQLException {
        validate(key, 64, "key");
        validate(value, 512, "value");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO aurum_runtime_state(state_key, state_value) VALUES (?, ?)
                     ON DUPLICATE KEY UPDATE state_value = VALUES(state_value)
                     """)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private static void validate(String value, int limit, String name) {
        if (value == null || value.isBlank() || value.length() > limit
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid runtime state " + name);
        }
    }
}
