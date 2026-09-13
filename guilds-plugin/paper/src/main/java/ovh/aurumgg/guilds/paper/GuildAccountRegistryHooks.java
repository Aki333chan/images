package ovh.aurumgg.guilds.paper;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.guilds.core.BankOnDisband;
import ovh.aurumgg.guilds.core.BankResult;
import ovh.aurumgg.guilds.core.GuildHooks;
import ovh.aurumgg.guilds.core.GuildService;

/** Core-free wrapper: AurumGuilds still loads in its documented Vault legacy mode. */
final class GuildAccountRegistryHooks implements GuildHooks, Listener {
    private static final String SERVICE = "ovh.aurumgg.core.api.AurumAccountRegistryApi";
    private final Plugin plugin;
    private final Supplier<GuildService> guilds;
    private final BooleanSupplier ledgerMode;
    private final Set<Long> deleting = ConcurrentHashMap.newKeySet();
    private volatile AurumGuildAccountRegistryBridge bridge;

    GuildAccountRegistryHooks(Plugin plugin, Supplier<GuildService> guilds,
                              BooleanSupplier ledgerMode) {
        this.plugin = plugin;
        this.guilds = guilds;
        this.ledgerMode = ledgerMode;
    }

    void start() { attach(); }

    @EventHandler
    public void onServiceRegistered(ServiceRegisterEvent event) {
        if (event.getProvider().getService().getName().equals(SERVICE)) attach();
    }

    private void attach() {
        if (plugin.getServer().getPluginManager().getPlugin("AurumCore") == null) return;
        try {
            AurumGuildAccountRegistryBridge candidate = new AurumGuildAccountRegistryBridge(plugin, guilds);
            if (!candidate.available()) return;
            bridge = candidate;
            candidate.synchronizeAll();
        } catch (LinkageError ignored) {
            bridge = null;
        }
    }

    private void synchronize(long guildId) {
        AurumGuildAccountRegistryBridge current = bridge;
        if (current == null || !current.available()) { attach(); current = bridge; }
        if (current != null) current.synchronize(guildId);
    }

    @Override
    public BankResult prepareGuildDeletion(long guildId, BankOnDisband mode) {
        // Vault legacy mode не имеет managed profile и не должен зависеть от Core.
        if (!ledgerMode.getAsBoolean()) return BankResult.success();
        AurumGuildAccountRegistryBridge current = bridge;
        if (current == null || !current.available()) { attach(); current = bridge; }
        if (current == null || !current.available()) return BankResult.fail("guild.err.accountLifecycle");
        BankResult result = current.prepareDeletion(guildId, mode);
        if (result.ok()) deleting.add(guildId);
        return result;
    }

    @Override public void guildCreated(long guildId, String tag) { synchronize(guildId); }
    @Override public void guildDeleted(long guildId) { deleting.remove(guildId); }
    @Override public void tagChanged(long guildId, String tag) { synchronize(guildId); }
    @Override public void memberJoined(long guildId, java.util.UUID player) { synchronize(guildId); }
    @Override public void memberLeft(long guildId, java.util.UUID player) {
        if (!deleting.contains(guildId)) synchronize(guildId);
    }
    @Override public void guildUpdated(long guildId) { synchronize(guildId); }
}
