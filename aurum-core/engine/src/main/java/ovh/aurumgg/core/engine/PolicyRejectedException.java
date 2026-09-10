package ovh.aurumgg.core.engine;

public final class PolicyRejectedException extends RuntimeException {
    public PolicyRejectedException(String message) {
        super(message);
    }
}
