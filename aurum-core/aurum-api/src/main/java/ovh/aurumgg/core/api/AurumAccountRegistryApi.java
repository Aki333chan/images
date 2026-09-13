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
    CompletionStage<ManagedAccountMutationResult> setFrozen(ManagedAccountStateRequest request);
    CompletionStage<ManagedAccountMutationResult> close(ManagedAccountCloseRequest request);
}
