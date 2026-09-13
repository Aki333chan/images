package ovh.aurumgg.core.api;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Managed account metadata and trusted administration over the existing ledger. */
public interface AurumAccountRegistryApi {
    CompletionStage<ManagedAccountPage> list(ManagedAccountQuery query);
    CompletionStage<Optional<ManagedAccount>> find(String profileKey);
    CompletionStage<ManagedAccountMutationResult> register(ManagedAccountRegistration request);
    /** Create the profile or refresh mutable presentation/controller metadata; founder and ledger members stay immutable. */
    CompletionStage<ManagedAccountMutationResult> synchronize(ManagedAccountRegistration request);
    CompletionStage<ManagedAccountMutationResult> transfer(ManagedAccountTransferRequest request);
    /**
     * Add money to a member from the system source, or take it into the system sink.
     *
     * <p>Separate from {@link #transfer} because it is not a move but a change of
     * the money supply. Implementations refuse technical profiles and anything
     * that is not ACTIVE, exactly as a transfer does.</p>
     */
    default CompletionStage<ManagedAccountMutationResult> adjust(ManagedAccountAdjustRequest request) {
        throw new UnsupportedOperationException("Managed-account adjustment is not supported");
    }
    CompletionStage<ManagedAccountMutationResult> setFrozen(ManagedAccountStateRequest request);
    CompletionStage<ManagedAccountMutationResult> close(ManagedAccountCloseRequest request);
}
