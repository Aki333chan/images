package ovh.aurumgg.core.engine;

/** Expected business rejection used while preparing a hold against an inactive managed account. */
final class AccountStatusRejectedException extends RuntimeException {
    AccountStatusRejectedException(String message) { super(message); }
}
