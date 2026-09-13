package ovh.aurumgg.guilds.paper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.AurumAccountRegistryApi;
import ovh.aurumgg.core.api.ManagedAccountMember;
import ovh.aurumgg.core.api.ManagedAccountMutationResult;
import ovh.aurumgg.core.api.ManagedAccountRegistration;
import ovh.aurumgg.guilds.core.GuildService;
import ovh.aurumgg.guilds.core.StoredGuild;

/** Native managed-account metadata; this class is loaded only when AurumCore is present. */
final class AurumGuildAccountRegistryBridge {
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
        String id = Long.toString(guild.id());
        String revision = UUID.nameUUIDFromBytes((guild.name() + "\u0000" + guild.tag() + "\u0000"
                + guild.leader()).getBytes(StandardCharsets.UTF_8)).toString();
        api.synchronize(new ManagedAccountRegistration(
                "guild-account-sync:" + id + ":" + revision, "guild:" + id, "GUILD", guild.name(),
                "Guild treasury [" + guild.tag() + "]", "PLAYER", guild.leader().toString(),
                guild.leader().toString(), "AurumGuilds", "GUILD", id, "treasury:global", false,
                List.of(new ManagedAccountMember(new AccountId(AccountType.GUILD, id), "primary", 0)),
                "system:AurumGuilds", "synchronize guild account"))
                .thenAccept(result -> report(guild, result));
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
