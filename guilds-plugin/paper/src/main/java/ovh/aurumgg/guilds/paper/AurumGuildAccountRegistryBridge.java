package ovh.aurumgg.guilds.paper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.AurumAccountRegistryApi;
import ovh.aurumgg.core.api.ManagedAccount;
import ovh.aurumgg.core.api.ManagedAccountCloseRequest;
import ovh.aurumgg.core.api.ManagedAccountMember;
import ovh.aurumgg.core.api.ManagedAccountMutationResult;
import ovh.aurumgg.core.api.ManagedAccountRegistration;
import ovh.aurumgg.core.api.ManagedAccountStateRequest;
import ovh.aurumgg.core.api.ManagedAccountStatus;
import ovh.aurumgg.guilds.core.BankOnDisband;
import ovh.aurumgg.guilds.core.BankResult;
import ovh.aurumgg.guilds.core.GuildService;
import ovh.aurumgg.guilds.core.StoredGuild;

/** Native managed-account metadata; this class is loaded only when AurumCore is present. */
final class AurumGuildAccountRegistryBridge {
    private static final long TIMEOUT_SECONDS = 5;
    private final Plugin plugin;
    private final Supplier<GuildService> guilds;

    AurumGuildAccountRegistryBridge(Plugin plugin, Supplier<GuildService> guilds) {
        this.plugin = plugin;
        this.guilds = guilds;
    }

    boolean available() { return lookup() != null; }

    void synchronizeAll() {
        GuildService service = guilds.get();
        if (service != null) service.allGuilds().forEach(this::synchronize);
    }

    void synchronize(long guildId) {
        GuildService service = guilds.get();
        if (service != null) service.byId(guildId).ifPresent(this::synchronize);
    }

    private void synchronize(StoredGuild guild) {
        AurumAccountRegistryApi api = lookup();
        if (api == null) return;
        api.synchronize(registration(guild))
                .thenAccept(result -> report(guild, result));
    }

    /** Runs on the GuildService worker, never on the Paper main thread. */
    BankResult prepareDeletion(long guildId, BankOnDisband mode) {
        AurumAccountRegistryApi api = lookup();
        GuildService service = guilds.get();
        StoredGuild guild = service == null ? null : service.byId(guildId).orElse(null);
        if (api == null || guild == null) return BankResult.fail("guild.err.accountLifecycle");
        String profileKey = "guild:" + guildId;
        try {
            ManagedAccount account = await(api.find(profileKey)).orElse(null);
            if (account == null) {
                ManagedAccountMutationResult synchronizedAccount = await(api.synchronize(registration(guild)));
                if (!accepted(synchronizedAccount)) {
                    report(guild, synchronizedAccount);
                    return BankResult.fail("guild.err.accountLifecycle");
                }
                account = synchronizedAccount.account();
            }
            if (account == null) return BankResult.fail("guild.err.accountLifecycle");

            ManagedAccountMutationResult result;
            if (mode == BankOnDisband.KEEP) {
                if (account.status() == ManagedAccountStatus.FROZEN
                        || account.status() == ManagedAccountStatus.CLOSED) return BankResult.success();
                if (account.status() != ManagedAccountStatus.ACTIVE) {
                    return BankResult.fail("guild.err.accountLifecycle");
                }
                result = await(api.setFrozen(new ManagedAccountStateRequest(
                        "guild-account-retire:" + guildId, profileKey, true,
                        "system:AurumGuilds", "preserve disbanded guild account")));
            } else {
                if (account.status() == ManagedAccountStatus.CLOSED) return BankResult.success();
                result = await(api.close(new ManagedAccountCloseRequest(
                        "guild-account-close:" + guildId, profileKey, "",
                        "system:AurumGuilds", "guild disband completed")));
            }
            if (accepted(result)) return BankResult.success();
            report(guild, result);
            return BankResult.fail("guild.err.accountLifecycle");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            plugin.getLogger().warning("Could not finalize managed account for guild " + guildId
                    + ": " + rootMessage(failure));
            return BankResult.fail("guild.err.accountLifecycle");
        }
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static ManagedAccountRegistration registration(StoredGuild guild) {
        String id = Long.toString(guild.id());
        String revision = UUID.nameUUIDFromBytes((guild.name() + "\u0000" + guild.tag() + "\u0000"
                + guild.leader()).getBytes(StandardCharsets.UTF_8)).toString();
        return new ManagedAccountRegistration(
                "guild-account-sync:" + id + ":" + revision, "guild:" + id, "GUILD", guild.name(),
                "Guild treasury [" + guild.tag() + "]", "PLAYER", guild.leader().toString(),
                guild.leader().toString(), "AurumGuilds", "GUILD", id, "treasury:global", false,
                List.of(new ManagedAccountMember(new AccountId(AccountType.GUILD, id), "primary", 0)),
                "system:AurumGuilds", "synchronize guild account");
    }

    private static boolean accepted(ManagedAccountMutationResult result) {
        return result.status() == ManagedAccountMutationResult.Status.SUCCESS
                || result.status() == ManagedAccountMutationResult.Status.DUPLICATE;
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private void report(StoredGuild guild, ManagedAccountMutationResult result) {
        if (result.status() != ManagedAccountMutationResult.Status.SUCCESS
                && result.status() != ManagedAccountMutationResult.Status.DUPLICATE) {
            plugin.getLogger().warning("Could not synchronize managed account for guild " + guild.id()
                    + ": " + result.message());
        }
    }

    private AurumAccountRegistryApi lookup() {
        RegisteredServiceProvider<AurumAccountRegistryApi> registration =
                plugin.getServer().getServicesManager().getRegistration(AurumAccountRegistryApi.class);
        return registration == null ? null : registration.getProvider();
    }
}
