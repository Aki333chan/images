package dev.addons.npc;

import dev.addons.npc.command.NpcCommand;
import dev.addons.npc.config.NpcRepository;
import dev.addons.npc.config.ShopRepository;
import dev.addons.npc.config.BuyerRepository;
import dev.addons.npc.config.GuildTraderRepository;
import dev.addons.npc.listener.InteractionListener;
import dev.addons.npc.listener.ProtectionListener;
import dev.addons.npc.listener.NpcSpawnBypassListener;
import dev.addons.npc.listener.OrphanNpcCleanupListener;
import dev.addons.npc.listener.NpcWorldLoadListener;
import dev.addons.npc.platform.MannequinAdapter;
import dev.addons.npc.service.ActionExecutor;
import dev.addons.npc.service.DialogueService;
import dev.addons.npc.service.EconomyService;
import dev.addons.npc.service.MessageService;
import dev.addons.npc.service.NpcManager;
import dev.addons.npc.service.ShopService;
import dev.addons.npc.service.BuyerService;
import dev.addons.npc.service.AurumGuildsHook;
import dev.addons.npc.service.GuildTraderService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class AddonsNpcPlugin extends JavaPlugin {
    private NpcRepository npcRepository;
    private ShopRepository shopRepository;
    private BuyerRepository buyerRepository;
    private GuildTraderRepository guildTraderRepository;
    private EconomyService economy;
    private MessageService messages;
    private DialogueService dialogues;
    private NpcManager npcManager;
    private AurumGuildsHook guildsHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        npcRepository = new NpcRepository(this);
        shopRepository = new ShopRepository(this);
        buyerRepository = new BuyerRepository(this);
        guildTraderRepository = new GuildTraderRepository(this);
        npcRepository.load();
        shopRepository.load();
        buyerRepository.load();
        guildTraderRepository.load();

        messages = new MessageService(this);
        economy = new EconomyService(this);
        boolean vault = economy.hook();
        dialogues = new DialogueService(messages);
        MannequinAdapter adapter = new MannequinAdapter(this);
        npcManager = new NpcManager(this, npcRepository, new dev.addons.npc.service.SkinService(this, adapter), adapter);
        ShopService shopService = new ShopService(this, shopRepository, economy, messages);
        BuyerService buyerService = new BuyerService(this, buyerRepository, economy, messages);
        guildsHook = new AurumGuildsHook(this);
        GuildTraderService guildTraderService = new GuildTraderService(this, guildTraderRepository, economy, messages, guildsHook);
        ActionExecutor actionExecutor = new ActionExecutor(messages, shopService, buyerService, guildTraderService);

        getServer().getPluginManager().registerEvents(shopService, this);
        getServer().getPluginManager().registerEvents(buyerService, this);
        getServer().getPluginManager().registerEvents(guildTraderService, this);
        getServer().getPluginManager().registerEvents(
                new InteractionListener(this, npcManager, dialogues, actionExecutor, economy, messages), this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(this, npcManager), this);
        getServer().getPluginManager().registerEvents(new NpcSpawnBypassListener(this, npcManager), this);
        getServer().getPluginManager().registerEvents(new OrphanNpcCleanupListener(npcManager), this);
        getServer().getPluginManager().registerEvents(new NpcWorldLoadListener(this, npcManager), this);

        PluginCommand command = getCommand("npc");
        if (command == null) {
            throw new IllegalStateException("Command 'npc' is missing from plugin.yml");
        }
        NpcCommand handler = new NpcCommand(this, npcRepository, shopRepository, buyerRepository, guildTraderRepository,
                npcManager, shopService, buyerService, guildTraderService, messages);
        command.setExecutor(handler);
        command.setTabCompleter(handler);

        long startupDelay = Math.max(1L, getConfig().getLong("settings.startup-spawn-delay-ticks", 20L));
        getServer().getScheduler().runTaskLater(this, npcManager::start, startupDelay);
        getLogger().info("Enabled " + npcRepository.ids().size() + " NPC(s) and " + shopRepository.ids().size()
                + " shop(s), " + buyerRepository.ids().size() + " buyer(s), and "
                + guildTraderRepository.ids().size() + " guild trader(s). Vault economy: "
                + (vault ? "connected" : "unavailable") + "; AurumGuilds: "
                + (guildsHook.available() ? "connected" : "unavailable"));
    }

    @Override
    public void onDisable() {
        if (npcRepository != null) npcRepository.save();
        if (shopRepository != null) shopRepository.save();
        if (buyerRepository != null) buyerRepository.save();
        if (guildTraderRepository != null) guildTraderRepository.save();
        if (npcManager != null) npcManager.stop();
    }

    public void reloadEverything() {
        reloadConfig();
        npcRepository.load();
        shopRepository.load();
        buyerRepository.load();
        guildTraderRepository.load();
        dialogues.clear();
        economy.hook();
        npcManager.syncAll();
    }

    public EconomyService economy() {
        return economy;
    }

    public boolean guildsAvailable() {
        return guildsHook != null && guildsHook.available();
    }
}
