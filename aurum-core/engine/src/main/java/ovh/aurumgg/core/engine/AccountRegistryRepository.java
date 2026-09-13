package ovh.aurumgg.core.engine;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.ManagedAccount;
import ovh.aurumgg.core.api.ManagedAccountPage;
import ovh.aurumgg.core.api.ManagedAccountQuery;
import ovh.aurumgg.core.api.ManagedAccountRegistration;
import ovh.aurumgg.core.api.ManagedAccountStatus;

public interface AccountRegistryRepository {
    enum WriteStatus { SUCCESS, DUPLICATE, NOT_FOUND, CONFLICT, REJECTED }

    record WriteResult(WriteStatus status, String message) {}
    record PendingClose(String idempotencyKey, String profileKey, String destinationProfile,
                        String actor, String reason) {}

    ManagedAccountPage list(ManagedAccountQuery query, Map<String, CurrencySpec> currencies) throws SQLException;
    Optional<ManagedAccount> find(String profileKey, Map<String, CurrencySpec> currencies) throws SQLException;
    WriteResult register(ManagedAccountRegistration request, String intentHash) throws SQLException;
    WriteResult synchronize(ManagedAccountRegistration request, String intentHash) throws SQLException;
    WriteResult setStatus(String idempotencyKey, String intentHash, String profileKey,
                          ManagedAccountStatus expectedFirst, ManagedAccountStatus expectedSecond,
                          ManagedAccountStatus target, String operation, String actor, String reason)
            throws SQLException;
    WriteResult beginClose(String idempotencyKey, String intentHash, String profileKey,
                           String destinationProfile, String actor, String reason) throws SQLException;
    void completeClose(String idempotencyKey, String profileKey) throws SQLException;
    Optional<PendingClose> pendingClosure(String profileKey) throws SQLException;
    List<PendingClose> pendingClosures(int limit) throws SQLException;
    boolean hasUnresolvedHolds(List<AccountId> members) throws SQLException;
    Map<AccountId, ManagedAccountStatus> memberStatuses() throws SQLException;
}
