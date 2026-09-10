package ovh.aurumgg.core.engine.db;

public record MariaDbSettings(String jdbcUrl, String username, String password, int poolSize) {
    public MariaDbSettings {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mariadb://")) {
            throw new IllegalArgumentException("A MariaDB JDBC URL is required");
        }
        if (username == null || password == null || poolSize < 1 || poolSize > 16) {
            throw new IllegalArgumentException("Invalid MariaDB settings");
        }
    }
}
