package ovh.aurumgg.guilds.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.AurumAccountRegistryApi;
import ovh.aurumgg.core.api.ManagedAccount;
import ovh.aurumgg.core.api.ManagedAccountCloseRequest;
import ovh.aurumgg.core.api.ManagedAccountMember;
import ovh.aurumgg.core.api.ManagedAccountMutationResult;
import ovh.aurumgg.core.api.ManagedAccountPage;
import ovh.aurumgg.core.api.ManagedAccountQuery;
import ovh.aurumgg.core.api.ManagedAccountRegistration;
import ovh.aurumgg.core.api.ManagedAccountStateRequest;
import ovh.aurumgg.core.api.ManagedAccountStatus;
import ovh.aurumgg.core.api.ManagedAccountTransferRequest;
import ovh.aurumgg.guilds.api.GuildSettings;
import ovh.aurumgg.guilds.core.BankOnDisband;
import ovh.aurumgg.guilds.core.GuildService;
import ovh.aurumgg.guilds.core.StoredGuild;

class AurumGuildAccountRegistryBridgeTest {
    private static final UUID LEADER = UUID.nameUUIDFromBytes("leader".getBytes());
    private final StoredGuild guild = new StoredGuild(7, "Dragons", "DRG", LEADER, 0,
            Instant.EPOCH, GuildSettings.defaults(), List.of());
    private FakeRegistry registry;
    private AurumGuildAccountRegistryBridge bridge;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new FakeRegistry();
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        ServicesManager services = mock(ServicesManager.class);
        RegisteredServiceProvider<AurumAccountRegistryApi> registration = mock(RegisteredServiceProvider.class);
        GuildService guilds = mock(GuildService.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("guild-registry-test"));
        when(server.getServicesManager()).thenReturn(services);
        when(services.getRegistration(AurumAccountRegistryApi.class)).thenReturn(registration);
        when(registration.getProvider()).thenReturn(registry);
        when(guilds.byId(7)).thenReturn(Optional.of(guild));
        bridge = new AurumGuildAccountRegistryBridge(plugin, () -> guilds);
    }

    @Test
    void ordinaryDisbandCreatesMissingProfileThenClosesIt() {
        assertTrue(bridge.prepareDeletion(7, BankOnDisband.LEADER).ok());
        assertNotNull(registry.synchronizedAccount);
        assertEquals("guild:7", registry.synchronizedAccount.profileKey());
        assertNotNull(registry.closeRequest);
        assertEquals("guild-account-close:7", registry.closeRequest.idempotencyKey());
        assertEquals("", registry.closeRequest.destinationProfile());
        assertEquals(ManagedAccountStatus.CLOSED, registry.account.status());
    }

    @Test
    void keepFreezesTheOrphanAccountInsteadOfSweepingIt() {
        registry.account = account(ManagedAccountStatus.ACTIVE);
        assertTrue(bridge.prepareDeletion(7, BankOnDisband.KEEP).ok());
        assertNotNull(registry.stateRequest);
        assertTrue(registry.stateRequest.frozen());
        assertEquals(ManagedAccountStatus.FROZEN, registry.account.status());
        assertEquals(null, registry.closeRequest);
    }

    @Test
    void rejectedClosePreventsGuildDeletion() {
        registry.account = account(ManagedAccountStatus.ACTIVE);
        registry.rejectClose = true;
        assertFalse(bridge.prepareDeletion(7, BankOnDisband.TREASURY).ok());
        assertEquals(ManagedAccountStatus.ACTIVE, registry.account.status());
    }

    private static ManagedAccount account(ManagedAccountStatus status) {
        return new ManagedAccount("guild:7", "GUILD", "Dragons", "Guild treasury [DRG]",
                "PLAYER", LEADER.toString(), LEADER.toString(), "AurumGuilds", "GUILD", "7",
                status, "treasury:global", false,
                List.of(new ManagedAccountMember(new AccountId(AccountType.GUILD, "7"), "primary", 0)),
                Map.of("primary", BigDecimal.ZERO), Instant.EPOCH, Instant.EPOCH,
                status == ManagedAccountStatus.CLOSED ? Instant.EPOCH : null);
    }

    private static final class FakeRegistry implements AurumAccountRegistryApi {
        private ManagedAccount account;
        private ManagedAccountRegistration synchronizedAccount;
        private ManagedAccountCloseRequest closeRequest;
        private ManagedAccountStateRequest stateRequest;
        private boolean rejectClose;

        @Override public CompletionStage<ManagedAccountPage> list(ManagedAccountQuery query) {
            return CompletableFuture.completedFuture(new ManagedAccountPage(List.of(), 0, 1, 0));
        }
        @Override public CompletionStage<Optional<ManagedAccount>> find(String profileKey) {
            return CompletableFuture.completedFuture(Optional.ofNullable(account));
        }
        @Override public CompletionStage<ManagedAccountMutationResult> register(ManagedAccountRegistration request) {
            throw new UnsupportedOperationException();
        }
        @Override public CompletionStage<ManagedAccountMutationResult> synchronize(ManagedAccountRegistration request) {
            synchronizedAccount = request;
            account = account(ManagedAccountStatus.ACTIVE);
            return completed(ManagedAccountMutationResult.Status.SUCCESS, account, "created");
        }
        @Override public CompletionStage<ManagedAccountMutationResult> transfer(ManagedAccountTransferRequest request) {
            throw new UnsupportedOperationException();
        }
        @Override public CompletionStage<ManagedAccountMutationResult> setFrozen(ManagedAccountStateRequest request) {
            stateRequest = request;
            account = account(ManagedAccountStatus.FROZEN);
            return completed(ManagedAccountMutationResult.Status.SUCCESS, account, "frozen");
        }
        @Override public CompletionStage<ManagedAccountMutationResult> close(ManagedAccountCloseRequest request) {
            closeRequest = request;
            if (rejectClose) {
                return completed(ManagedAccountMutationResult.Status.REJECTED, account, "unresolved-holds");
            }
            account = account(ManagedAccountStatus.CLOSED);
            return completed(ManagedAccountMutationResult.Status.SUCCESS, account, "closed");
        }
        private static CompletionStage<ManagedAccountMutationResult> completed(
                ManagedAccountMutationResult.Status status, ManagedAccount account, String message) {
            return CompletableFuture.completedFuture(new ManagedAccountMutationResult(status, account, message));
        }
    }
}
