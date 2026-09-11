package dev.addons.npc;

import dev.addons.npc.command.NpcCommand;
import dev.addons.npc.config.NpcRepository;
import dev.addons.npc.config.ShopRepository;
import dev.addons.npc.config.BuyerRepository;
import dev.addons.npc.config.GuildTraderRepository;
import dev.addons.npc.config.ExchangerRepository;
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
import dev.addons.npc.service.AurumExchangeService;
import dev.addons.npc.service.NpcSagaRepository;
import dev.addons.npc.model.BuyerDefinition;
import dev.addons.npc.model.BuyerOffer;
import dev.addons.npc.model.ClickMode;
import dev.addons.npc.model.DialogueMode;
import dev.addons.npc.model.LookMode;
import dev.addons.npc.model.NpcDefinition;
import dev.addons.npc.model.ShopDefinition;
import dev.addons.npc.model.ShopOffer;
import dev.addons.npc.model.SkinSpec;
import dev.addons.npc.model.StoredLocation;
import dev.addons.npc.model.TimedPercentage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class AddonsNpcPlugin extends JavaPlugin {
    private NpcRepository npcRepository;
    private ShopRepository shopRepository;
    private BuyerRepository buyerRepository;
    private GuildTraderRepository guildTraderRepository;
    private ExchangerRepository exchangerRepository;
    private EconomyService economy;
    private MessageService messages;
    private DialogueService dialogues;
    private NpcManager npcManager;
    private AurumGuildsHook guildsHook;
    private ShopService shopService;
    private BuyerService buyerService;
    private AurumExchangeService exchangerService;
    private NpcSagaRepository sagas;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        npcRepository = new NpcRepository(this);
        shopRepository = new ShopRepository(this);
        buyerRepository = new BuyerRepository(this);
        guildTraderRepository = new GuildTraderRepository(this);
        exchangerRepository = new ExchangerRepository(this);
        npcRepository.load();
        shopRepository.load();
        buyerRepository.load();
        guildTraderRepository.load();
        exchangerRepository.load();

        messages = new MessageService(this);
        sagas = new NpcSagaRepository(this);
        economy = new EconomyService(this);
        boolean nativeEconomy = economy.hook();
        dialogues = new DialogueService(messages);
        MannequinAdapter adapter = new MannequinAdapter(this);
        npcManager = new NpcManager(this, npcRepository, new dev.addons.npc.service.SkinService(this, adapter), adapter);
        shopService = new ShopService(this, shopRepository, economy, messages, sagas);
        buyerService = new BuyerService(this, buyerRepository, economy, messages, sagas);
        guildsHook = new AurumGuildsHook(this);
        GuildTraderService guildTraderService = new GuildTraderService(this, guildTraderRepository, economy,
                messages, guildsHook, sagas);
        exchangerService = new AurumExchangeService(this, exchangerRepository, messages);
        boolean aurumExchange = exchangerService.hook();
        ActionExecutor actionExecutor = new ActionExecutor(messages, shopService, buyerService,
                guildTraderService, exchangerService);

        getServer().getPluginManager().registerEvents(shopService, this);
        getServer().getPluginManager().registerEvents(buyerService, this);
        getServer().getPluginManager().registerEvents(guildTraderService, this);
        getServer().getPluginManager().registerEvents(exchangerService, this);
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
        NpcCommand handler = new NpcCommand(this, npcRepository, shopRepository, buyerRepository,
                guildTraderRepository, exchangerRepository, npcManager, shopService, buyerService,
                guildTraderService, exchangerService, messages);
        command.setExecutor(handler);
        command.setTabCompleter(handler);

        long startupDelay = Math.max(1L, getConfig().getLong("settings.startup-spawn-delay-ticks", 20L));
        getServer().getScheduler().runTaskLater(this, npcManager::start, startupDelay);
        long recoveryPeriod = Math.max(5L, getConfig().getLong("economy.recovery-retry-seconds", 20L)) * 20L;
        getServer().getScheduler().runTaskTimer(this,
                () -> economy.recover(sagas, guildsHook), recoveryPeriod, recoveryPeriod);
        getLogger().info("Enabled " + npcRepository.ids().size() + " NPC(s) and " + shopRepository.ids().size()
                + " shop(s), " + buyerRepository.ids().size() + " buyer(s), and "
                + guildTraderRepository.ids().size() + " guild trader(s), "
                + exchangerRepository.ids().size() + " exchanger(s). AurumCore holds: "
                + (nativeEconomy ? "connected" : "unavailable") + "; AurumGuilds: "
                + (guildsHook.available() ? "connected" : "unavailable") + "; Aurum exchange: "
                + (aurumExchange ? "connected" : "unavailable"));
    }

    @Override
    public void onDisable() {
        if (npcRepository != null) npcRepository.save();
        if (shopRepository != null) shopRepository.save();
        if (buyerRepository != null) buyerRepository.save();
        if (guildTraderRepository != null) guildTraderRepository.save();
        if (exchangerRepository != null) exchangerRepository.save();
        if (npcManager != null) npcManager.stop();
    }

    public void reloadEverything() {
        reloadConfig();
        messages.reload();
        npcRepository.load();
        shopRepository.load();
        buyerRepository.load();
        guildTraderRepository.load();
        exchangerRepository.load();
        dialogues.clear();
        economy.hook();
        economy.recover(sagas, guildsHook);
        exchangerService.hook();
        npcManager.syncAll();
    }

    public EconomyService economy() {
        return economy;
    }

    public boolean guildsAvailable() {
        return guildsHook != null && guildsHook.available();
    }

    /** Typed snapshots consumed reflectively by AurumCompanion, without a hard dependency. */
    public List<Map<String, String>> aurumAdminSnapshot(Player viewer, String scope) {
        if (!viewer.hasPermission("addonsnpc.admin")) return List.of();
        if ("npc".equals(scope)) return npcRepository.all().stream().map(this::npcCard).toList();
        if ("shop".equals(scope)) return shopRepository.all().stream().map(this::shopCard).toList();
        if ("buyer".equals(scope)) return buyerRepository.all().stream().map(this::buyerCard).toList();
        if (scope.startsWith("shop-offer:")) {
            ShopDefinition shop = shopRepository.get(scope.substring("shop-offer:".length()));
            return shop == null ? List.of() : shop.offers().values().stream().map(offer -> shopOfferCard(shop, offer)).toList();
        }
        if (scope.startsWith("buyer-offer:")) {
            BuyerDefinition buyer = buyerRepository.get(scope.substring("buyer-offer:".length()));
            return buyer == null ? List.of() : buyer.offers().values().stream().map(offer -> buyerOfferCard(buyer, offer)).toList();
        }
        return List.of();
    }

    /** Whitelisted mutation API for the mod UI. Arbitrary commands are deliberately unsupported. */
    public String aurumAdminAction(Player actor, String id, String action, Map<String, String> arguments) {
        if (!actor.hasPermission("addonsnpc.admin")) return "error.permission";
        try {
            if (action.startsWith("npc_")) return npcAction(actor, id, action, arguments);
            if (action.startsWith("shop_offer_")) return shopOfferAction(actor, id, action, arguments);
            if (action.startsWith("buyer_offer_")) return buyerOfferAction(actor, id, action, arguments);
            if (action.startsWith("shop_")) return shopAction(actor, id, action, arguments);
            if (action.startsWith("buyer_")) return buyerAction(actor, id, action, arguments);
            return "error.unknown_action";
        } catch (IllegalArgumentException exception) {
            return "error.invalid_value";
        }
    }

    private Map<String, String> npcCard(NpcDefinition npc) {
        Map<String, String> value = card("npc", npc.id(), npc.name());
        value.put("description", npc.description());
        value.put("enabled", String.valueOf(npc.enabled()));
        value.put("entityType", npc.entityType().name());
        value.put("location", npc.location().world() + " " + block(npc.location().x()) + " "
                + block(npc.location().y()) + " " + block(npc.location().z()));
        value.put("yaw", number(npc.location().yaw()));
        value.put("pitch", number(npc.location().pitch()));
        value.put("clickMode", npc.clickMode().name());
        value.put("dialogueMode", npc.dialogueMode().name());
        value.put("cooldown", number(npc.cooldownSeconds()));
        value.put("permission", npc.permission());
        value.put("look", String.valueOf(npc.lookAtPlayers()));
        value.put("lookMode", npc.lookMode().name());
        value.put("lookRange", number(npc.lookRange()));
        value.put("visibility", number(npc.visibilityRange()));
        value.put("nameRange", number(npc.nameVisibilityRange()));
        value.put("pose", npc.pose().name());
        value.put("rightHand", material(npc.rightHand()));
        value.put("leftHand", material(npc.leftHand()));
        value.put("skinType", npc.skin().type().name());
        value.put("skinValue", npc.skin().value());
        return Map.copyOf(value);
    }

    private Map<String, String> shopCard(ShopDefinition shop) {
        Map<String, String> value = card("shop", shop.id(), shop.title());
        value.put("size", String.valueOf(shop.size()));
        value.put("offers", String.valueOf(shop.offers().size()));
        percentage(value, "discount", shop.discount());
        return Map.copyOf(value);
    }

    private Map<String, String> buyerCard(BuyerDefinition buyer) {
        Map<String, String> value = card("buyer", buyer.id(), buyer.title());
        value.put("size", String.valueOf(buyer.size()));
        value.put("offers", String.valueOf(buyer.offers().size()));
        percentage(value, "bonus", buyer.bonus());
        return Map.copyOf(value);
    }

    private Map<String, String> shopOfferCard(ShopDefinition shop, ShopOffer offer) {
        Map<String, String> value = card("shopOffer", shop.id() + ":" + offer.slot(),
                "#" + offer.slot() + " " + offer.item().name());
        value.put("parent", shop.id());
        value.put("slot", String.valueOf(offer.slot()));
        value.put("material", offer.item().name());
        value.put("name", offer.displayName());
        value.put("price", number(offer.price()));
        value.put("quantity", String.valueOf(offer.quantity()));
        value.put("stock", offer.unlimited() ? "-1" : String.valueOf(offer.stock()));
        value.put("match", offer.productTemplate() == null ? "MATERIAL" : "EXACT");
        percentage(value, "discount", offer.discount());
        return Map.copyOf(value);
    }

    private Map<String, String> buyerOfferCard(BuyerDefinition buyer, BuyerOffer offer) {
        Map<String, String> value = card("buyerOffer", buyer.id() + ":" + offer.slot(),
                "#" + offer.slot() + " " + offer.template().getType().name());
        value.put("parent", buyer.id());
        value.put("slot", String.valueOf(offer.slot()));
        value.put("material", offer.template().getType().name());
        value.put("name", offer.displayName());
        value.put("price", number(offer.unitPrice()));
        value.put("bulkAmount", String.valueOf(offer.bulkAmount()));
        value.put("bulkPrice", number(offer.bulkPrice()));
        value.put("match", offer.matchMode().name());
        percentage(value, "bonus", offer.bonus());
        return Map.copyOf(value);
    }

    private String npcAction(Player actor, String id, String action, Map<String, String> args) {
        NpcDefinition npc = npcRepository.get(id);
        if (npc == null) return "error.not_found";
        switch (action) {
            case "npc_teleport" -> {
                var location = npc.location().resolve();
                if (location == null) return "error.no_location";
                actor.teleport(location);
            }
            case "npc_move_here" -> npc.location(StoredLocation.from(actor.getLocation()));
            case "npc_rotation_here" -> npc.location(new StoredLocation(npc.location().world(), npc.location().x(),
                    npc.location().y(), npc.location().z(), actor.getLocation().getYaw(), actor.getLocation().getPitch()));
            case "npc_rotation_reset" -> npcManager.resetRotation(npc);
            case "npc_toggle_enabled" -> npc.enabled(!npc.enabled());
            case "npc_toggle_look" -> npc.lookAtPlayers(!npc.lookAtPlayers());
            case "npc_cycle_look_mode" -> npc.lookMode(npc.lookMode() == LookMode.HEAD ? LookMode.BODY : LookMode.HEAD);
            case "npc_cycle_click_mode" -> npc.clickMode(next(npc.clickMode(), ClickMode.values()));
            case "npc_cycle_dialogue_mode" -> npc.dialogueMode(next(npc.dialogueMode(), DialogueMode.values()));
            case "npc_set_name" -> npc.name(text(args, "value", 128));
            case "npc_set_description" -> npc.description(text(args, "value", 256));
            case "npc_set_cooldown" -> npc.cooldownSeconds(decimal(args, "value", 0, 86_400));
            case "npc_set_permission" -> npc.permission(text(args, "value", 128));
            case "npc_set_look_range" -> npc.lookRange(decimal(args, "value", 0, 512));
            case "npc_set_visibility" -> npc.visibilityRange(decimal(args, "value", 0, 512));
            case "npc_set_name_range" -> npc.nameVisibilityRange(decimal(args, "value", 0, 512));
            case "npc_set_entity_type" -> npc.entityType(EntityType.valueOf(text(args, "value", 48).toUpperCase(Locale.ROOT)));
            case "npc_set_pose" -> npc.pose(Pose.valueOf(text(args, "value", 48).toUpperCase(Locale.ROOT)));
            case "npc_right_hand" -> npc.rightHand(hand(actor));
            case "npc_left_hand" -> npc.leftHand(hand(actor));
            case "npc_clear_right_hand" -> npc.rightHand(null);
            case "npc_clear_left_hand" -> npc.leftHand(null);
            case "npc_set_skin" -> npc.skin(new SkinSpec(SkinSpec.Type.valueOf(
                    text(args, "type", 24).toUpperCase(Locale.ROOT)), text(args, "value", 512)));
            default -> { return "error.unknown_action"; }
        }
        npcRepository.save();
        npcManager.sync(npc);
        return "ok.saved";
    }

    private String shopAction(Player actor, String id, String action, Map<String, String> args) {
        ShopDefinition shop = shopRepository.get(id);
        if (shop == null) return "error.not_found";
        switch (action) {
            case "shop_open" -> { shopService.open(actor, id); return "ok.opened"; }
            case "shop_set_title" -> shop.title(text(args, "value", 128));
            case "shop_set_discount" -> shop.discount(timed(args, 100));
            default -> { return "error.unknown_action"; }
        }
        shopRepository.save();
        return "ok.saved";
    }

    private String buyerAction(Player actor, String id, String action, Map<String, String> args) {
        BuyerDefinition buyer = buyerRepository.get(id);
        if (buyer == null) return "error.not_found";
        switch (action) {
            case "buyer_open" -> { buyerService.open(actor, id); return "ok.opened"; }
            case "buyer_set_title" -> buyer.title(text(args, "value", 128));
            case "buyer_set_bonus" -> buyer.bonus(timed(args, 1000));
            default -> { return "error.unknown_action"; }
        }
        buyerRepository.save();
        return "ok.saved";
    }

    private String shopOfferAction(Player actor, String composite, String action, Map<String, String> args) {
        String[] key = offerKey(composite);
        ShopDefinition shop = shopRepository.get(key[0]);
        if (shop == null) return "error.not_found";
        int slot = Integer.parseInt(key[1]);
        ShopOffer offer = shop.offers().get(slot);
        if (action.equals("shop_offer_create_from_hand")) {
            if (slot < 0 || slot >= shop.size()) return "error.invalid_value";
            if (offer != null && !Boolean.parseBoolean(args.getOrDefault("replace", "false"))) return "error.replace_confirmation";
            ItemStack held = hand(actor);
            int stock = integer(args, "stock", -1, Integer.MAX_VALUE);
            double price = decimal(args, "price", 0, 1_000_000_000_000.0);
            offer = new ShopOffer(slot, held.getType(), stock <= 0 ? -1 : stock, price);
            offer.product(held);
            offer.quantity(integer(args, "quantity", 1, held.getMaxStackSize()));
            shop.offers().put(slot, offer);
        } else {
            if (offer == null) return "error.not_found";
            switch (action) {
                case "shop_offer_set_price" -> offer.price(decimal(args, "value", 0, 1_000_000_000_000.0));
                case "shop_offer_set_quantity" -> offer.quantity(integer(args, "value", 1, offer.item().getMaxStackSize()));
                case "shop_offer_set_stock" -> { int value = integer(args, "value", -1, Integer.MAX_VALUE); offer.stock(value <= 0 ? -1 : value); }
                case "shop_offer_set_name" -> offer.displayName(text(args, "value", 128));
                case "shop_offer_set_discount" -> offer.discount(timed(args, 100));
                case "shop_offer_item_from_hand" -> {
                    ItemStack held = hand(actor);
                    offer.product(held);
                    offer.quantity(Math.min(offer.quantity(), held.getMaxStackSize()));
                }
                case "shop_offer_remove" -> shop.offers().remove(slot);
                default -> { return "error.unknown_action"; }
            }
        }
        shopRepository.save();
        return "ok.saved";
    }

    private String buyerOfferAction(Player actor, String composite, String action, Map<String, String> args) {
        String[] key = offerKey(composite);
        BuyerDefinition buyer = buyerRepository.get(key[0]);
        if (buyer == null) return "error.not_found";
        int slot = Integer.parseInt(key[1]);
        BuyerOffer offer = buyer.offers().get(slot);
        if (action.equals("buyer_offer_create_from_hand")) {
            if (slot < 0 || slot >= buyer.size()) return "error.invalid_value";
            if (offer != null && !Boolean.parseBoolean(args.getOrDefault("replace", "false"))) return "error.replace_confirmation";
            offer = new BuyerOffer(slot, hand(actor), decimal(args, "price", 0.00000001, 1_000_000_000_000.0));
            int bulkAmount = integer(args, "bulkAmount", 0, 2304);
            if (bulkAmount > 1) offer.bulk(bulkAmount, decimal(args, "bulkPrice", 0.00000001, 1_000_000_000_000.0));
            offer.matchMode(BuyerOffer.MatchMode.parse(args.getOrDefault("match", "material")));
            buyer.offers().put(slot, offer);
        } else {
            if (offer == null) return "error.not_found";
            switch (action) {
                case "buyer_offer_set_price" -> offer.unitPrice(decimal(args, "value", 0.00000001, 1_000_000_000_000.0));
                case "buyer_offer_set_bulk" -> {
                    int amount = integer(args, "amount", 0, 2304);
                    offer.bulk(amount, amount <= 1 ? 0 : decimal(args, "price", 0.00000001, 1_000_000_000_000.0));
                }
                case "buyer_offer_toggle_match" -> offer.matchMode(offer.matchMode() == BuyerOffer.MatchMode.MATERIAL
                        ? BuyerOffer.MatchMode.EXACT : BuyerOffer.MatchMode.MATERIAL);
                case "buyer_offer_set_name" -> offer.displayName(text(args, "value", 128));
                case "buyer_offer_set_bonus" -> offer.bonus(timed(args, 1000));
                case "buyer_offer_item_from_hand" -> offer.template(hand(actor));
                case "buyer_offer_remove" -> buyer.offers().remove(slot);
                default -> { return "error.unknown_action"; }
            }
        }
        buyerRepository.save();
        return "ok.saved";
    }

    private static Map<String, String> card(String kind, String id, String title) {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("kind", kind);
        value.put("id", id);
        value.put("title", title == null || title.isBlank() ? id : title);
        return value;
    }

    private static void percentage(Map<String, String> value, String name, TimedPercentage percentage) {
        long now = System.currentTimeMillis();
        value.put(name, number(percentage.active(now) ? percentage.percent() : 0));
        value.put(name + "Remaining", percentage.active(now) ? percentage.remaining(now) : "off");
    }

    private static TimedPercentage timed(Map<String, String> args, double maximum) {
        double percent = decimal(args, "percent", 0, maximum);
        long seconds = integer(args, "duration", 0, 315_360_000);
        return new TimedPercentage(percent, percent <= 0 || seconds == 0 ? 0 : System.currentTimeMillis() + seconds * 1000L);
    }

    private static ItemStack hand(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) throw new IllegalArgumentException("empty hand");
        return item.clone();
    }

    private static String[] offerKey(String composite) {
        int separator = composite.lastIndexOf(':');
        if (separator < 1 || separator == composite.length() - 1) throw new IllegalArgumentException("offer key");
        return new String[]{composite.substring(0, separator), composite.substring(separator + 1)};
    }

    private static int integer(Map<String, String> args, String key, int minimum, int maximum) {
        int value = Integer.parseInt(args.getOrDefault(key, ""));
        if (value < minimum || value > maximum) throw new IllegalArgumentException(key);
        return value;
    }

    private static double decimal(Map<String, String> args, String key, double minimum, double maximum) {
        double value = Double.parseDouble(args.getOrDefault(key, ""));
        if (!Double.isFinite(value) || value < minimum || value > maximum) throw new IllegalArgumentException(key);
        return value;
    }

    private static String text(Map<String, String> args, String key, int maximum) {
        String value = args.getOrDefault(key, "").trim();
        if (value.length() > maximum) throw new IllegalArgumentException(key);
        return value;
    }

    private static <T extends Enum<T>> T next(T current, T[] values) {
        return values[(current.ordinal() + 1) % values.length];
    }

    private static int block(double value) { return (int) Math.floor(value); }
    private static String number(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
    private static String material(ItemStack item) { return item == null ? "—" : item.getType().name(); }
}
