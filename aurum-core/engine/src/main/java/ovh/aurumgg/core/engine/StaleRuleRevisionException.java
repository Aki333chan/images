package ovh.aurumgg.core.engine;

import java.sql.SQLException;

/** Optimistic write guard failed while the row was locked in MariaDB. */
public final class StaleRuleRevisionException extends SQLException {
    private final long expected;
    private final long actual;

    public StaleRuleRevisionException(long expected, long actual) {
        super("Rule revision changed: expected " + expected + ", current " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public long expected() { return expected; }
    public long actual() { return actual; }
}
