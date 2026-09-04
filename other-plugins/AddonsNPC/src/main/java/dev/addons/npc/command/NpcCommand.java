package dev.addons.npc.command;

import dev.addons.npc.AddonsNpcPlugin;
import dev.addons.npc.config.NpcRepository;
import dev.addons.npc.config.ShopRepository;
import dev.addons.npc.config.BuyerRepository;
import dev.addons.npc.config.GuildTraderRepository;
import dev.addons.npc.model.ActionDefinition;
import dev.addons.npc.model.ClickMode;
import dev.addons.npc.model.BuyerDefinition;
import dev.addons.npc.model.BuyerOffer;
import dev.addons.npc.model.DialogueMode;
import dev.addons.npc.model.NpcDefinition;
import dev.addons.npc.model.ShopDefinition;
import dev.addons.npc.model.ShopOffer;
import dev.addons.npc.model.SkinSpec;
import dev.addons.npc.model.StoredLocation;
import dev.addons.npc.model.LookMode;
import dev.addons.npc.model.TimedPercentage;
import dev.addons.npc.model.GuildBonusOffer;
import dev.addons.npc.model.GuildBonusType;
import dev.addons.npc.model.GuildRankRequirement;
import dev.addons.npc.model.GuildTraderDefinition;
import dev.addons.npc.service.MessageService;
import dev.addons.npc.service.NpcManager;
import dev.addons.npc.service.ShopService;
import dev.addons.npc.service.BuyerService;
import dev.addons.npc.service.GuildTraderService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

public final class NpcCommand implements CommandExecutor, TabCompleter {
    private final AddonsNpcPlugin plugin;
    private final NpcRepository npcs;
    private final ShopRepository shops;
    private final BuyerRepository buyers;
    private final GuildTraderRepository guildTraders;
    private final NpcManager manager;
    private final ShopService shopService;
    private final BuyerService buyerService;
    private final GuildTraderService guildTraderService;
    private final MessageService messages;
    private final Map<String, PendingReplacement> pendingReplacements = new HashMap<>();

    public NpcCommand(AddonsNpcPlugin plugin, NpcRepository npcs, ShopRepository shops, BuyerRepository buyers,
                      GuildTraderRepository guildTraders, NpcManager manager, ShopService shopService,
                      BuyerService buyerService, GuildTraderService guildTraderService, MessageService messages) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.shops = shops;
        this.buyers = buyers;
        this.guildTraders = guildTraders;
        this.manager = manager;
        this.shopService = shopService;
        this.buyerService = buyerService;
        this.guildTraderService = guildTraderService;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("addonsnpc.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        clearMismatchedReplacement(sender, args);
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> create(sender, args);
                case "delete" -> delete(sender, args);
                case "cleanup", "purge" -> cleanup(sender, args);
                case "list" -> list(sender);
                case "info" -> info(sender, args);
                case "move" -> move(sender, args);
                case "tp", "teleport" -> teleport(sender, args);
                case "enable" -> enable(sender, args, true);
                case "disable" -> enable(sender, args, false);
                case "name" -> name(sender, args);
                case "description", "desc" -> description(sender, args);
                case "type" -> type(sender, args);
                case "skin" -> skin(sender, args);
                case "equipment", "equip" -> equipment(sender, args);
                case "rotation", "rotate" -> rotation(sender, args);
                case "message", "dialogue" -> dialogue(sender, args);
                case "action" -> action(sender, args);
                case "set" -> set(sender, args);
                case "shop" -> shop(sender, args);
                case "buyer" -> buyer(sender, args);
                case "guildtrader", "guildshop" -> guildTrader(sender, args);
                case "reload" -> reload(sender);
                default -> {
                    help(sender);
                    yield true;
                }
            };
        } catch (IllegalArgumentException exception) {
            messages.raw(sender, messages.prefix() + "&c" + exception.getMessage(), Map.of());
            return true;
        }
    }

    private boolean create(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        require(args, 2, "/npc create <id> [name]");
        String id = NpcDefinition.normalizeId(args[1]);
        if (npcs.get(id) != null) {
            throw new IllegalArgumentException("NPC '" + id + "' already exists.");
        }
        String name = args.length > 2 ? join(args, 2) : "&e" + id;
        NpcDefinition npc = new NpcDefinition(id, StoredLocation.from(player.getLocation()), name);
        npc.cooldownSeconds(plugin.getConfig().getDouble("settings.default-cooldown-seconds", 1.0));
        npc.visibilityRange(plugin.getConfig().getDouble("settings.default-visibility-range", 48.0));
        npc.nameVisibilityRange(plugin.getConfig().getDouble("settings.default-name-visibility-range", 24.0));
        npc.lookMode(LookMode.parse(plugin.getConfig().getString("settings.default-look-mode", "HEAD")));
        npcs.put(npc);
        saveAndSync(npc);
        ok(sender, "Created NPC &e" + id + "&a at your location.");
        return true;
    }

    private boolean delete(CommandSender sender, String[] args) {
        NpcDefinition npc = requireNpc(args);
        manager.delete(npc.id());
        ok(sender, "Deleted NPC &e" + npc.id() + "&a.");
        return true;
    }

    private boolean cleanup(CommandSender sender, String[] args) {
        require(args, 2, "/npc cleanup <id|orphans>");
        if (args[1].equalsIgnoreCase("orphans")) {
            int removed = manager.cleanupLoadedOrphans();
            ok(sender, "Removed &e" + removed + "&a orphaned loaded NPC entity/entities.");
            return true;
        }
        String id = NpcDefinition.normalizeId(args[1]);
        if (npcs.get(id) != null) {
            throw new IllegalArgumentException("NPC '" + id
                    + "' still exists in config. Use /npc delete " + id + " instead.");
        }
        int removed = manager.purgePhysical(id);
        if (removed == 0) {
            throw new IllegalArgumentException("No loaded orphan entities tagged '" + id
                    + "' were found. Stand near it to load its chunk, then repeat the command.");
        }
        ok(sender, "Removed &e" + removed + "&a physical entity/entities tagged &e" + id + "&a.");
        return true;
    }

    private boolean list(CommandSender sender) {
        String ids = npcs.ids().isEmpty() ? "&7none" : "&e" + String.join("&7, &e", npcs.ids());
        messages.raw(sender, messages.prefix() + "&6NPCs (&e" + npcs.ids().size() + "&6): " + ids, Map.of());
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        NpcDefinition npc = requireNpc(args);
        messages.raw(sender, "&6--- NPC " + npc.id() + " ---", Map.of());
        messages.raw(sender, "&7Name: &f" + npc.name() + " &7Type: &f" + npc.entityType()
                + " &7Enabled: &f" + npc.enabled(), Map.of());
        messages.raw(sender, "&7World: &f" + npc.location().world() + " &7XYZ: &f"
                + round(npc.location().x()) + ", " + round(npc.location().y()) + ", " + round(npc.location().z()), Map.of());
        messages.raw(sender, "&7Skin: &f" + npc.skin().type() + " " + npc.skin().value(), Map.of());
        messages.raw(sender, "&7Click: &f" + npc.clickMode() + " &7Cooldown: &f" + npc.cooldownSeconds()
                + "s &7Permission: &f" + (npc.permission().isBlank() ? "none" : npc.permission()), Map.of());
        messages.raw(sender, "&7Visibility: &f" + npc.visibilityRange() + " &7Name: &f" + npc.nameVisibilityRange()
                + " &7Look: &f" + npc.lookMode() + " " + npc.lookRange(), Map.of());
        messages.raw(sender, "&7Dialogue: &f" + npc.dialogueMode() + " (&e" + npc.messages().size()
                + "&f lines) &7Actions: &f" + npc.actions().size(), Map.of());
        messages.raw(sender, "&7Hands: &fR=" + itemName(npc.rightHand()) + " &7L=&f" + itemName(npc.leftHand())
                + " &7Base rotation: &f" + round(npc.location().yaw()) + "/" + round(npc.location().pitch()), Map.of());
        return true;
    }

    private boolean move(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        NpcDefinition npc = requireNpc(args);
        npc.location(StoredLocation.from(player.getLocation()));
        saveAndSync(npc);
        ok(sender, "Moved NPC &e" + npc.id() + "&a to your location.");
        return true;
    }

    private boolean teleport(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        NpcDefinition npc = requireNpc(args);
        if (npc.location().resolve() == null) {
            throw new IllegalArgumentException("NPC world is not loaded.");
        }
        player.teleport(npc.location().resolve());
        ok(sender, "Teleported to NPC &e" + npc.id() + "&a.");
        return true;
    }

    private boolean enable(CommandSender sender, String[] args, boolean enabled) {
        NpcDefinition npc = requireNpc(args);
        npc.enabled(enabled);
        saveAndSync(npc);
        ok(sender, (enabled ? "Enabled" : "Disabled") + " NPC &e" + npc.id() + "&a.");
        return true;
    }

    private boolean name(CommandSender sender, String[] args) {
        require(args, 3, "/npc name <id> <name>");
        NpcDefinition npc = requireNpc(args);
        npc.name(join(args, 2));
        saveAndSync(npc);
        ok(sender, "Updated NPC name.");
        return true;
    }

    private boolean description(CommandSender sender, String[] args) {
        require(args, 3, "/npc description <id> <text|clear>");
        NpcDefinition npc = requireNpc(args);
        npc.description(args[2].equalsIgnoreCase("clear") ? "" : join(args, 2));
        saveAndSync(npc);
        ok(sender, "Updated NPC description.");
        return true;
    }

    private boolean type(CommandSender sender, String[] args) {
        require(args, 3, "/npc type <id> <entity-type>");
        NpcDefinition npc = requireNpc(args);
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown entity type: " + args[2]);
        }
        npc.entityType(entityType);
        saveAndSync(npc);
        ok(sender, "Changed NPC &e" + npc.id() + "&a to &e" + entityType.name() + "&a.");
        return true;
    }

    private boolean skin(CommandSender sender, String[] args) {
        require(args, 3, "/npc skin <id> <player|url|clear> [value]");
        NpcDefinition npc = requireNpc(args);
        String type = args[2].toLowerCase(Locale.ROOT);
        SkinSpec skin = switch (type) {
            case "clear", "none" -> SkinSpec.none();
            case "player" -> {
                require(args, 4, "/npc skin <id> player <name>");
                yield new SkinSpec(SkinSpec.Type.PLAYER, args[3]);
            }
            case "url" -> {
                require(args, 4, "/npc skin <id> url <https-url>");
                yield new SkinSpec(SkinSpec.Type.URL, args[3]);
            }
            default -> throw new IllegalArgumentException("Skin type must be player, url or clear.");
        };
        npc.skin(skin);
        saveAndSync(npc);
        ok(sender, skin.type() == SkinSpec.Type.PLAYER
                ? "Skin lookup started; it will be applied asynchronously." : "Updated NPC skin.");
        return true;
    }

    private boolean equipment(CommandSender sender, String[] args) {
        require(args, 4, "/npc equipment <id> <right|left> <material|hand|clear>");
        NpcDefinition npc = requireNpc(args);
        ItemStack item;
        if (args[3].equalsIgnoreCase("clear") || args[3].equalsIgnoreCase("none")) item = null;
        else if (args[3].equalsIgnoreCase("hand")) item = requireHeldItem(requirePlayer(sender));
        else {
            Material material = Material.matchMaterial(args[3]);
            if (material == null || !material.isItem()) throw new IllegalArgumentException("Unknown item material: " + args[3]);
            item = new ItemStack(material);
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "right", "main", "mainhand" -> npc.rightHand(item);
            case "left", "off", "offhand" -> npc.leftHand(item);
            default -> throw new IllegalArgumentException("Hand must be right/mainhand or left/offhand.");
        }
        saveAndSync(npc);
        ok(sender, "Updated NPC equipment.");
        return true;
    }

    private boolean rotation(CommandSender sender, String[] args) {
        require(args, 3, "/npc rotation <id> <reset|set>");
        NpcDefinition npc = requireNpc(args);
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "reset" -> manager.resetRotation(npc);
            case "set", "save" -> {
                Player player = requirePlayer(sender);
                StoredLocation old = npc.location();
                npc.location(new StoredLocation(old.world(), old.x(), old.y(), old.z(),
                        player.getLocation().getYaw(), player.getLocation().getPitch()));
                saveAndSync(npc);
            }
            default -> throw new IllegalArgumentException("Rotation operation must be reset or set.");
        }
        ok(sender, args[2].equalsIgnoreCase("reset")
                ? "Reset NPC rotation to its saved direction."
                : "Saved your viewing direction as the NPC base rotation.");
        return true;
    }

    private boolean dialogue(CommandSender sender, String[] args) {
        require(args, 3, "/npc message <add|remove|clear|mode|list> <id> ...");
        String operation = args[1].toLowerCase(Locale.ROOT);
        NpcDefinition npc = requireNpcAt(args, 2);
        switch (operation) {
            case "add" -> {
                require(args, 4, "/npc message add <id> <text>");
                npc.messages().add(join(args, 3));
            }
            case "remove" -> {
                require(args, 4, "/npc message remove <id> <index>");
                int index = integer(args[3], "index") - 1;
                if (index < 0 || index >= npc.messages().size()) {
                    throw new IllegalArgumentException("Message index is out of range.");
                }
                npc.messages().remove(index);
            }
            case "clear" -> npc.messages().clear();
            case "mode" -> {
                require(args, 4, "/npc message mode <id> <all|random|sequential>");
                npc.dialogueMode(DialogueMode.parse(args[3]));
            }
            case "list" -> {
                messages.raw(sender, "&6Dialogue lines for " + npc.id() + ":", Map.of());
                for (int i = 0; i < npc.messages().size(); i++) {
                    messages.raw(sender, "&e" + (i + 1) + ". &f" + npc.messages().get(i), Map.of());
                }
                return true;
            }
            default -> throw new IllegalArgumentException("Message operation must be add, remove, clear, mode or list.");
        }
        npcs.save();
        ok(sender, "Updated dialogue for NPC &e" + npc.id() + "&a.");
        return true;
    }

    private boolean action(CommandSender sender, String[] args) {
        require(args, 3, "/npc action <add|remove|clear|list> <id> ...");
        String operation = args[1].toLowerCase(Locale.ROOT);
        NpcDefinition npc = requireNpcAt(args, 2);
        switch (operation) {
            case "add" -> {
                require(args, 5, "/npc action add <id> <message|console|player|shop|buyer|guildtrader|sound|title> <value>");
                npc.actions().add(ActionDefinition.parse(args[3] + ':' + join(args, 4)));
            }
            case "remove" -> {
                require(args, 4, "/npc action remove <id> <index>");
                int index = integer(args[3], "index") - 1;
                if (index < 0 || index >= npc.actions().size()) {
                    throw new IllegalArgumentException("Action index is out of range.");
                }
                npc.actions().remove(index);
            }
            case "clear" -> npc.actions().clear();
            case "list" -> {
                messages.raw(sender, "&6Actions for " + npc.id() + ":", Map.of());
                for (int i = 0; i < npc.actions().size(); i++) {
                    messages.raw(sender, "&e" + (i + 1) + ". &f" + npc.actions().get(i).serialize(), Map.of());
                }
                return true;
            }
            default -> throw new IllegalArgumentException("Action operation must be add, remove, clear or list.");
        }
        npcs.save();
        ok(sender, "Updated actions for NPC &e" + npc.id() + "&a.");
        return true;
    }

    private boolean set(CommandSender sender, String[] args) {
        require(args, 4, "/npc set <id> <cooldown|permission|click|look|lookrange|lookmode|visibility|namerange|pose> <value>");
        NpcDefinition npc = requireNpc(args);
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "cooldown" -> npc.cooldownSeconds(decimal(args[3], "cooldown"));
            case "permission" -> npc.permission(args[3].equalsIgnoreCase("none") ? "" : args[3]);
            case "click" -> npc.clickMode(ClickMode.parse(args[3]));
            case "look" -> npc.lookAtPlayers(bool(args[3]));
            case "lookrange" -> npc.lookRange(decimal(args[3], "look range"));
            case "lookmode" -> npc.lookMode(LookMode.parse(args[3]));
            case "visibility", "viewrange" -> npc.visibilityRange(decimal(args[3], "visibility range"));
            case "namerange" -> npc.nameVisibilityRange(decimal(args[3], "name visibility range"));
            case "pose" -> npc.pose(Pose.valueOf(args[3].toUpperCase(Locale.ROOT)));
            default -> throw new IllegalArgumentException("Unknown NPC setting. Use /npc help.");
        }
        saveAndSync(npc);
        ok(sender, "Updated NPC setting.");
        return true;
    }

    private boolean shop(CommandSender sender, String[] args) {
        require(args, 2, "/npc shop <create|delete|list|open|offer|quantity|discount|remove> ...");
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                messages.raw(sender, messages.prefix() + "&6Shops: &e" + String.join("&7, &e", shops.ids()), Map.of());
                yield true;
            }
            case "create" -> {
                require(args, 3, "/npc shop create <id> [title]");
                String id = NpcDefinition.normalizeId(args[2]);
                if (shops.get(id) != null) throw new IllegalArgumentException("Shop already exists.");
                shops.put(new ShopDefinition(id, args.length > 3 ? join(args, 3) : "&8" + id, 27));
                shops.save();
                ok(sender, "Created shop &e" + id + "&a.");
                yield true;
            }
            case "delete" -> {
                require(args, 3, "/npc shop delete <id>");
                if (shops.remove(args[2]) == null) throw new IllegalArgumentException("Shop was not found.");
                shops.save();
                ok(sender, "Deleted shop &e" + args[2] + "&a.");
                yield true;
            }
            case "open" -> {
                Player player = requirePlayer(sender);
                require(args, 3, "/npc shop open <id>");
                shopService.open(player, args[2]);
                yield true;
            }
            case "offer" -> shopOffer(sender, args);
            case "quantity" -> shopQuantity(sender, args);
            case "discount" -> shopDiscount(sender, args);
            case "remove" -> shopRemove(sender, args);
            default -> throw new IllegalArgumentException("Unknown shop operation. Use /npc help.");
        };
    }

    private boolean shopOffer(CommandSender sender, String[] args) {
        require(args, 7, "/npc shop offer <shop> <slot> <price> <material[:potion-type]|hand> <amount> [display name]");
        ShopDefinition shop = requireShop(args[2]);
        int slot = integer(args[3], "slot");
        if (slot < 0 || slot >= shop.size()) throw new IllegalArgumentException("Slot must be between 0 and " + (shop.size() - 1) + '.');
        double price = decimal(args[4], "price");
        ItemStack product = shopProduct(sender, args[5]);
        Material material = product.getType();
        int amount = integer(args[6], "amount");
        ShopOffer offer = new ShopOffer(slot, material, amount <= 0 ? -1 : amount, price);
        offer.product(product);
        if (args.length > 7) offer.displayName(join(args, 7));
        if (shop.offers().containsKey(slot) && !replacementConfirmed(sender, slot, args)) return true;
        shop.offers().put(slot, offer);
        shops.save();
        ok(sender, "Saved offer in shop &e" + shop.id() + "&a.");
        return true;
    }

    private boolean shopQuantity(CommandSender sender, String[] args) {
        require(args, 5, "/npc shop quantity <shop> <slot> <quantity>");
        ShopDefinition shop = requireShop(args[2]);
        ShopOffer offer = shop.offers().get(integer(args[3], "slot"));
        if (offer == null) throw new IllegalArgumentException("Offer was not found.");
        int quantity = integer(args[4], "quantity");
        if (quantity < 1 || quantity > offer.item().getMaxStackSize()) {
            throw new IllegalArgumentException("Quantity must be between 1 and " + offer.item().getMaxStackSize() + '.');
        }
        offer.quantity(quantity);
        shops.save();
        ok(sender, "Updated purchase quantity.");
        return true;
    }

    private ItemStack shopProduct(CommandSender sender, String raw) {
        if (raw.equalsIgnoreCase("hand")) return requireHeldItem(requirePlayer(sender));
        Material material = Material.matchMaterial(raw);
        String potionName = null;
        if (material == null) {
            int separator = raw.lastIndexOf(':');
            if (separator > 0) {
                material = Material.matchMaterial(raw.substring(0, separator));
                potionName = raw.substring(separator + 1);
            }
        }
        if (material == null || !material.isItem()) throw new IllegalArgumentException("Unknown shop item: " + raw);
        ItemStack result = new ItemStack(material);
        if (potionName != null) {
            if (!(result.getItemMeta() instanceof PotionMeta meta)) {
                throw new IllegalArgumentException("Potion type can only be used with POTION, SPLASH_POTION, LINGERING_POTION or TIPPED_ARROW.");
            }
            PotionType type;
            try { type = PotionType.valueOf(potionName.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown potion type: " + potionName); }
            meta.setBasePotionType(type);
            result.setItemMeta(meta);
        }
        return result;
    }

    private boolean shopDiscount(CommandSender sender, String[] args) {
        require(args, 5, "/npc shop discount <shop> <all|slot> <percent|off> [duration]");
        ShopDefinition shop = requireShop(args[2]);
        TimedPercentage discount = percentage(args[4], args.length > 5 ? args[5] : "permanent", 100, "discount");
        if (args[3].equalsIgnoreCase("all")) shop.discount(discount);
        else {
            ShopOffer offer = shop.offers().get(integer(args[3], "slot"));
            if (offer == null) throw new IllegalArgumentException("Offer was not found.");
            offer.discount(discount);
        }
        shops.save();
        ok(sender, discount.percent() == 0 ? "Disabled shop discount." : "Updated shop discount.");
        return true;
    }

    private boolean shopRemove(CommandSender sender, String[] args) {
        require(args, 4, "/npc shop remove <shop> <slot>");
        ShopDefinition shop = requireShop(args[2]);
        if (shop.offers().remove(integer(args[3], "slot")) == null) throw new IllegalArgumentException("Offer was not found.");
        shops.save();
        ok(sender, "Removed shop offer.");
        return true;
    }

    private boolean buyer(CommandSender sender, String[] args) {
        require(args, 2, "/npc buyer <create|delete|list|open|title|size|offer|price|bulk|bonus|match|name|lore|command|permission|remove> ...");
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                messages.raw(sender, messages.prefix() + "&6Buyers: &e" + String.join("&7, &e", buyers.ids()), Map.of());
                yield true;
            }
            case "create" -> {
                require(args, 3, "/npc buyer create <id> [title]");
                String id = NpcDefinition.normalizeId(args[2]);
                if (buyers.get(id) != null) throw new IllegalArgumentException("Buyer already exists.");
                buyers.put(new BuyerDefinition(id, args.length > 3 ? join(args, 3) : "&8" + id, 27));
                buyers.save(); ok(sender, "Created buyer &e" + id + "&a."); yield true;
            }
            case "delete" -> {
                require(args, 3, "/npc buyer delete <id>");
                if (buyers.remove(args[2]) == null) throw new IllegalArgumentException("Buyer was not found.");
                buyers.save(); ok(sender, "Deleted buyer &e" + args[2] + "&a."); yield true;
            }
            case "open" -> {
                Player player = requirePlayer(sender);
                require(args, 3, "/npc buyer open <id>");
                buyerService.open(player, args[2]); yield true;
            }
            case "title" -> buyerTitle(sender, args);
            case "size" -> buyerSize(sender, args);
            case "offer" -> buyerOffer(sender, args);
            case "price" -> buyerPrice(sender, args);
            case "bulk" -> buyerBulk(sender, args);
            case "bonus" -> buyerBonus(sender, args);
            case "match" -> buyerMatch(sender, args);
            case "name" -> buyerName(sender, args);
            case "lore" -> buyerLore(sender, args);
            case "command" -> buyerCommand(sender, args);
            case "permission" -> buyerPermission(sender, args);
            case "remove" -> buyerRemove(sender, args);
            default -> throw new IllegalArgumentException("Unknown buyer operation. Use /npc help.");
        };
    }

    private boolean buyerTitle(CommandSender sender, String[] args) {
        require(args, 4, "/npc buyer title <buyer> <title>");
        requireBuyer(args[2]).title(join(args, 3));
        buyers.save(); ok(sender, "Updated buyer title."); return true;
    }

    private boolean buyerSize(CommandSender sender, String[] args) {
        require(args, 4, "/npc buyer size <buyer> <9|18|27|36|45|54>");
        BuyerDefinition buyer = requireBuyer(args[2]);
        int size = integer(args[3], "size");
        if (size < 9 || size > 54 || size % 9 != 0) {
            throw new IllegalArgumentException("Buyer size must be 9, 18, 27, 36, 45 or 54.");
        }
        if (buyer.offers().keySet().stream().anyMatch(slot -> slot >= size)) {
            throw new IllegalArgumentException("Remove offers outside the new menu size first.");
        }
        buyer.size(size); buyers.save(); ok(sender, "Updated buyer menu size."); return true;
    }

    private boolean buyerOffer(CommandSender sender, String[] args) {
        require(args, 6, "/npc buyer offer <buyer> <slot> <unit-price> <material|hand> [bulk-amount] [bulk-price] [material|exact]");
        BuyerDefinition buyer = requireBuyer(args[2]);
        int slot = integer(args[3], "slot");
        if (slot < 0 || slot >= buyer.size()) throw new IllegalArgumentException("Slot must be between 0 and " + (buyer.size() - 1) + '.');
        double unitPrice = positiveDecimal(args[4], "unit price");
        boolean fromHand = args[5].equalsIgnoreCase("hand");
        ItemStack template;
        if (fromHand) {
            template = requireHeldItem(requirePlayer(sender));
        } else {
            Material material = Material.matchMaterial(args[5]);
            if (material == null || !material.isItem() || material.isAir()) throw new IllegalArgumentException("Unknown item material: " + args[5]);
            template = new ItemStack(material);
        }
        BuyerOffer offer = new BuyerOffer(slot, template, unitPrice);
        boolean bulkDisabled = args.length >= 7 && (args[6].equalsIgnoreCase("off") || args[6].equals("0"));
        if (args.length >= 7 && !bulkDisabled) {
            require(args, 8, "/npc buyer offer <buyer> <slot> <unit-price> <material|hand> <bulk-amount> <bulk-price> [material|exact]");
            offer.bulk(integer(args[6], "bulk amount"), positiveDecimal(args[7], "bulk price"));
        }
        String match = bulkDisabled && args.length >= 8 ? args[7]
                : args.length >= 9 ? args[8] : fromHand ? "exact" : "material";
        offer.matchMode(BuyerOffer.MatchMode.parse(match));
        if (buyer.offers().containsKey(slot) && !replacementConfirmed(sender, slot, args)) return true;
        buyer.offers().put(slot, offer); buyers.save();
        ok(sender, "Saved buyer offer in &e" + buyer.id() + "&a. Match: &e" + offer.matchMode() + "&a.");
        return true;
    }

    private boolean buyerBulk(CommandSender sender, String[] args) {
        require(args, 5, "/npc buyer bulk <buyer> <slot> <amount|off> [price]");
        BuyerOffer offer = requireBuyerOffer(args[2], args[3]);
        if (args[4].equalsIgnoreCase("off") || args[4].equals("0")) {
            offer.bulk(0, 0);
        } else {
            require(args, 6, "/npc buyer bulk <buyer> <slot> <amount> <price>");
            offer.bulk(integer(args[4], "bulk amount"), positiveDecimal(args[5], "bulk price"));
        }
        buyers.save(); ok(sender, offer.bulkEnabled() ? "Updated wholesale price." : "Disabled wholesale price.");
        return true;
    }

    private boolean buyerPrice(CommandSender sender, String[] args) {
        require(args, 5, "/npc buyer price <buyer> <slot> <unit-price>");
        requireBuyerOffer(args[2], args[3]).unitPrice(positiveDecimal(args[4], "unit price"));
        buyers.save(); ok(sender, "Updated unit price."); return true;
    }

    private boolean buyerBonus(CommandSender sender, String[] args) {
        require(args, 5, "/npc buyer bonus <buyer> <all|slot> <percent|off> [duration]");
        BuyerDefinition buyer = requireBuyer(args[2]);
        TimedPercentage bonus = percentage(args[4], args.length > 5 ? args[5] : "permanent", 1000, "bonus");
        if (args[3].equalsIgnoreCase("all")) buyer.bonus(bonus);
        else {
            BuyerOffer offer = buyer.offers().get(integer(args[3], "slot"));
            if (offer == null) throw new IllegalArgumentException("Buyer offer was not found.");
            offer.bonus(bonus);
        }
        buyers.save();
        ok(sender, bonus.percent() == 0 ? "Disabled buyer bonus." : "Updated buyer bonus.");
        return true;
    }

    private boolean buyerMatch(CommandSender sender, String[] args) {
        require(args, 5, "/npc buyer match <buyer> <slot> <material|exact>");
        BuyerOffer offer = requireBuyerOffer(args[2], args[3]);
        BuyerOffer.MatchMode mode = BuyerOffer.MatchMode.parse(args[4]);
        if (mode == BuyerOffer.MatchMode.EXACT) offer.template(requireHeldItem(requirePlayer(sender)));
        offer.matchMode(mode); buyers.save();
        ok(sender, "Updated buyer item matching to &e" + mode + "&a.");
        return true;
    }

    private boolean buyerName(CommandSender sender, String[] args) {
        require(args, 5, "/npc buyer name <buyer> <slot> <display-name>");
        requireBuyerOffer(args[2], args[3]).displayName(join(args, 4));
        buyers.save(); ok(sender, "Updated buyer offer name."); return true;
    }

    private boolean buyerLore(CommandSender sender, String[] args) {
        require(args, 5, "/npc buyer lore <buyer> <slot> <add|remove|clear|list> [text|index]");
        return editBuyerLines(sender, requireBuyerOffer(args[2], args[3]).lore(), args, "lore");
    }

    private boolean buyerCommand(CommandSender sender, String[] args) {
        require(args, 5, "/npc buyer command <buyer> <slot> <add|remove|clear|list> [command|index]");
        return editBuyerLines(sender, requireBuyerOffer(args[2], args[3]).commands(), args, "command");
    }

    private boolean editBuyerLines(CommandSender sender, List<String> lines, String[] args, String type) {
        switch (args[4].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                require(args, 6, "/npc buyer " + type + " <buyer> <slot> add <value>");
                lines.add(join(args, 5));
            }
            case "remove" -> {
                require(args, 6, "/npc buyer " + type + " <buyer> <slot> remove <index>");
                int index = integer(args[5], "index") - 1;
                if (index < 0 || index >= lines.size()) throw new IllegalArgumentException(type + " index is out of range.");
                lines.remove(index);
            }
            case "clear" -> lines.clear();
            case "list" -> {
                messages.raw(sender, "&6Buyer " + type + " entries:", Map.of());
                for (int index = 0; index < lines.size(); index++) {
                    messages.raw(sender, "&e" + (index + 1) + ". &f" + lines.get(index), Map.of());
                }
                return true;
            }
            default -> throw new IllegalArgumentException("Operation must be add, remove, clear or list.");
        }
        buyers.save(); ok(sender, "Updated buyer " + type + "."); return true;
    }

    private boolean buyerPermission(CommandSender sender, String[] args) {
        require(args, 5, "/npc buyer permission <buyer> <slot> <permission|none>");
        requireBuyerOffer(args[2], args[3]).permission(args[4].equalsIgnoreCase("none") ? "" : args[4]);
        buyers.save(); ok(sender, "Updated buyer offer permission."); return true;
    }

    private boolean buyerRemove(CommandSender sender, String[] args) {
        require(args, 4, "/npc buyer remove <buyer> <slot>");
        BuyerDefinition buyer = requireBuyer(args[2]);
        if (buyer.offers().remove(integer(args[3], "slot")) == null) throw new IllegalArgumentException("Buyer offer was not found.");
        buyers.save(); ok(sender, "Removed buyer offer."); return true;
    }

    private boolean guildTrader(CommandSender sender, String[] args) {
        require(args, 2, "/npc guildtrader <create|delete|list|open|title|size|rank|offer|price|icon|name|lore|permission|remove> ...");
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                messages.raw(sender, messages.prefix() + "&6Guild traders: &e"
                        + String.join("&7, &e", guildTraders.ids()), Map.of());
                yield true;
            }
            case "create" -> {
                require(args, 3, "/npc guildtrader create <id> [title]");
                String id = NpcDefinition.normalizeId(args[2]);
                if (guildTraders.get(id) != null) throw new IllegalArgumentException("Guild trader already exists.");
                guildTraders.put(new GuildTraderDefinition(id,
                        args.length > 3 ? join(args, 3) : "&8Усиления гильдии", 27));
                guildTraders.save(); ok(sender, "Created guild trader &e" + id + "&a."); yield true;
            }
            case "delete" -> {
                require(args, 3, "/npc guildtrader delete <id>");
                if (guildTraders.remove(args[2]) == null) throw new IllegalArgumentException("Guild trader was not found.");
                guildTraders.save(); ok(sender, "Deleted guild trader &e" + args[2] + "&a."); yield true;
            }
            case "open" -> {
                Player player = requirePlayer(sender);
                require(args, 3, "/npc guildtrader open <id>");
                guildTraderService.open(player, args[2]); yield true;
            }
            case "title" -> guildTraderTitle(sender, args);
            case "size" -> guildTraderSize(sender, args);
            case "rank" -> guildTraderRank(sender, args);
            case "offer" -> guildTraderOffer(sender, args);
            case "price" -> guildTraderPrice(sender, args);
            case "icon" -> guildTraderIcon(sender, args);
            case "name" -> guildTraderName(sender, args);
            case "lore" -> guildTraderLore(sender, args);
            case "permission" -> guildTraderPermission(sender, args);
            case "remove" -> guildTraderRemove(sender, args);
            default -> throw new IllegalArgumentException("Unknown guild trader operation. Use /npc help.");
        };
    }

    private boolean guildTraderTitle(CommandSender sender, String[] args) {
        require(args, 4, "/npc guildtrader title <trader> <title>");
        requireGuildTrader(args[2]).title(join(args, 3));
        guildTraders.save(); ok(sender, "Updated guild trader title."); return true;
    }

    private boolean guildTraderSize(CommandSender sender, String[] args) {
        require(args, 4, "/npc guildtrader size <trader> <9|18|27|36|45|54>");
        GuildTraderDefinition trader = requireGuildTrader(args[2]);
        int size = integer(args[3], "size");
        if (size < 9 || size > 54 || size % 9 != 0) throw new IllegalArgumentException("Menu size must be 9, 18, 27, 36, 45 or 54.");
        if (trader.offers().keySet().stream().anyMatch(slot -> slot >= size)) {
            throw new IllegalArgumentException("Remove offers outside the new menu size first.");
        }
        trader.size(size); guildTraders.save(); ok(sender, "Updated guild trader menu size."); return true;
    }

    private boolean guildTraderRank(CommandSender sender, String[] args) {
        require(args, 4, "/npc guildtrader rank <trader> <member|officer|leader>");
        GuildTraderDefinition trader = requireGuildTrader(args[2]);
        trader.requiredRank(GuildRankRequirement.parse(args[3]));
        guildTraders.save(); ok(sender, "Required guild rank: &e" + trader.requiredRank() + "&a."); return true;
    }

    private boolean guildTraderOffer(CommandSender sender, String[] args) {
        require(args, 8, "/npc guildtrader offer <trader> <slot> <type> <magnitude> <duration> <price> [name]");
        GuildTraderDefinition trader = requireGuildTrader(args[2]);
        int slot = integer(args[3], "slot");
        if (slot < 0 || slot >= trader.size()) throw new IllegalArgumentException("Slot must be between 0 and " + (trader.size() - 1) + '.');
        GuildBonusType type = GuildBonusType.parse(args[4]);
        double magnitude = positiveDecimal(args[5], "magnitude");
        long durationSeconds = durationMillis(args[6]) / 1000L;
        double price = decimal(args[7], "price");
        GuildBonusOffer offer = new GuildBonusOffer(slot, type, magnitude, durationSeconds, price);
        if (args.length > 8) offer.displayName(join(args, 8));
        if (trader.offers().containsKey(slot) && !replacementConfirmed(sender, slot, args)) return true;
        trader.offers().put(slot, offer); guildTraders.save();
        ok(sender, "Saved &e" + type.name().toLowerCase(Locale.ROOT) + "&a in guild trader &e" + trader.id() + "&a.");
        return true;
    }

    private boolean guildTraderPrice(CommandSender sender, String[] args) {
        require(args, 5, "/npc guildtrader price <trader> <slot> <price>");
        requireGuildBonusOffer(args[2], args[3]).price(decimal(args[4], "price"));
        guildTraders.save(); ok(sender, "Updated guild bonus price."); return true;
    }

    private boolean guildTraderIcon(CommandSender sender, String[] args) {
        require(args, 5, "/npc guildtrader icon <trader> <slot> <material|auto>");
        GuildBonusOffer offer = requireGuildBonusOffer(args[2], args[3]);
        if (args[4].equalsIgnoreCase("auto")) offer.icon(offer.type().defaultIcon());
        else {
            Material material = Material.matchMaterial(args[4]);
            if (material == null || !material.isItem()) throw new IllegalArgumentException("Unknown item material: " + args[4]);
            offer.icon(material);
        }
        guildTraders.save(); ok(sender, "Updated guild bonus icon."); return true;
    }

    private boolean guildTraderName(CommandSender sender, String[] args) {
        require(args, 5, "/npc guildtrader name <trader> <slot> <display-name>");
        requireGuildBonusOffer(args[2], args[3]).displayName(join(args, 4));
        guildTraders.save(); ok(sender, "Updated guild bonus name."); return true;
    }

    private boolean guildTraderLore(CommandSender sender, String[] args) {
        require(args, 5, "/npc guildtrader lore <trader> <slot> <add|remove|clear|list> [text|index]");
        List<String> lore = requireGuildBonusOffer(args[2], args[3]).lore();
        switch (args[4].toLowerCase(Locale.ROOT)) {
            case "add" -> { require(args, 6, "/npc guildtrader lore <trader> <slot> add <text>"); lore.add(join(args, 5)); }
            case "remove" -> {
                require(args, 6, "/npc guildtrader lore <trader> <slot> remove <index>");
                int index = integer(args[5], "index") - 1;
                if (index < 0 || index >= lore.size()) throw new IllegalArgumentException("Lore index is out of range.");
                lore.remove(index);
            }
            case "clear" -> lore.clear();
            case "list" -> {
                messages.raw(sender, "&6Guild bonus lore:", Map.of());
                for (int index = 0; index < lore.size(); index++) messages.raw(sender, "&e" + (index + 1) + ". &f" + lore.get(index), Map.of());
                return true;
            }
            default -> throw new IllegalArgumentException("Operation must be add, remove, clear or list.");
        }
        guildTraders.save(); ok(sender, "Updated guild bonus lore."); return true;
    }

    private boolean guildTraderPermission(CommandSender sender, String[] args) {
        require(args, 5, "/npc guildtrader permission <trader> <slot> <permission|none>");
        requireGuildBonusOffer(args[2], args[3]).permission(args[4].equalsIgnoreCase("none") ? "" : args[4]);
        guildTraders.save(); ok(sender, "Updated guild bonus permission."); return true;
    }

    private boolean guildTraderRemove(CommandSender sender, String[] args) {
        require(args, 4, "/npc guildtrader remove <trader> <slot>");
        GuildTraderDefinition trader = requireGuildTrader(args[2]);
        if (trader.offers().remove(integer(args[3], "slot")) == null) throw new IllegalArgumentException("Guild bonus offer was not found.");
        guildTraders.save(); ok(sender, "Removed guild bonus offer."); return true;
    }

    private boolean reload(CommandSender sender) {
        plugin.reloadEverything();
        ok(sender, "Reloaded config, NPCs, shops, buyers and guild traders. Vault: &e"
                + (plugin.economy().available() ? "connected" : "unavailable") + "&a, AurumGuilds: &e"
                + (plugin.guildsAvailable() ? "connected" : "unavailable"));
        return true;
    }

    private void help(CommandSender sender) {
        String[] lines = {
                "&6--- AddonsNPC commands ---",
                "&e/npc create <id> [name] &7- create at your position",
                "&e/npc delete|move|tp|enable|disable <id>",
                "&e/npc cleanup <id|orphans> &7- remove orphaned physical entities",
                "&e/npc name <id> <name> &7/ &e/npc description <id> <text|clear>",
                "&e/npc type <id> <entity-type> &7- mannequin, villager, animal or mob",
                "&e/npc skin <id> <player|url|clear> [value]",
                "&e/npc equipment <id> <right|left> <material|hand|clear>",
                "&e/npc rotation <id> <set|reset> &7- save or restore the base direction",
                "&e/npc message <add|remove|clear|mode|list> <id> ...",
                "&e/npc action <add|remove|clear|list> <id> ...",
                "&e/npc set <id> <cooldown|permission|click|look|lookrange|lookmode|visibility|namerange|pose> <value>",
                "&e/npc shop <create|delete|list|open|offer|quantity|discount|remove> ...",
                "&e/npc buyer <create|delete|list|open|title|size|offer|price|bulk|bonus|match|name|lore|command|permission|remove> ...",
                "&e/npc guildtrader <create|delete|list|open|title|size|rank|offer|price|icon|name|lore|permission|remove> ...",
                "&e/npc list &7/ &e/npc info <id> &7/ &e/npc reload"
        };
        for (String line : lines) messages.raw(sender, line, Map.of());
    }

    private NpcDefinition requireNpc(String[] args) {
        require(args, 2, "An NPC ID is required.");
        return requireNpcAt(args, 1);
    }

    private NpcDefinition requireNpcAt(String[] args, int index) {
        NpcDefinition npc = npcs.get(args[index]);
        if (npc == null) throw new IllegalArgumentException("NPC '" + args[index] + "' was not found.");
        return npc;
    }

    private ShopDefinition requireShop(String id) {
        ShopDefinition shop = shops.get(id);
        if (shop == null) throw new IllegalArgumentException("Shop '" + id + "' was not found.");
        return shop;
    }

    private BuyerDefinition requireBuyer(String id) {
        BuyerDefinition buyer = buyers.get(id);
        if (buyer == null) throw new IllegalArgumentException("Buyer '" + id + "' was not found.");
        return buyer;
    }

    private BuyerOffer requireBuyerOffer(String buyerId, String rawSlot) {
        BuyerOffer offer = requireBuyer(buyerId).offers().get(integer(rawSlot, "slot"));
        if (offer == null) throw new IllegalArgumentException("Buyer offer was not found.");
        return offer;
    }

    private GuildTraderDefinition requireGuildTrader(String id) {
        GuildTraderDefinition trader = guildTraders.get(id);
        if (trader == null) throw new IllegalArgumentException("Guild trader '" + id + "' was not found.");
        return trader;
    }

    private GuildBonusOffer requireGuildBonusOffer(String traderId, String rawSlot) {
        GuildBonusOffer offer = requireGuildTrader(traderId).offers().get(integer(rawSlot, "slot"));
        if (offer == null) throw new IllegalArgumentException("Guild bonus offer was not found.");
        return offer;
    }

    private static ItemStack requireHeldItem(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) throw new IllegalArgumentException("Hold the item in your main hand.");
        ItemStack template = item.clone(); template.setAmount(1); return template;
    }

    private Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("This command can only be used by a player.");
        return player;
    }

    private void saveAndSync(NpcDefinition npc) {
        npcs.save();
        manager.sync(npc);
    }

    private void ok(CommandSender sender, String text) {
        messages.raw(sender, messages.prefix() + "&a" + text, Map.of());
    }

    private boolean replacementConfirmed(CommandSender sender, int slot, String[] args) {
        long now = System.currentTimeMillis();
        String senderKey = sender.getName().toLowerCase(Locale.ROOT);
        String signature = String.join("\u0000", args);
        PendingReplacement pending = pendingReplacements.get(senderKey);
        if (pending != null && pending.expiresAt() >= now && pending.signature().equals(signature)) {
            pendingReplacements.remove(senderKey);
            return true;
        }
        PendingReplacement replacement = new PendingReplacement(signature, now + 30_000);
        pendingReplacements.put(senderKey, replacement);
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> pendingReplacements.remove(senderKey, replacement), 600L);
        messages.send(sender, "offer-replace-confirm", Map.of("slot", slot, "seconds", 30));
        return false;
    }

    private void clearMismatchedReplacement(CommandSender sender, String[] args) {
        String senderKey = sender.getName().toLowerCase(Locale.ROOT);
        PendingReplacement pending = pendingReplacements.get(senderKey);
        if (pending != null && (pending.expiresAt() < System.currentTimeMillis()
                || !pending.signature().equals(String.join("\u0000", args)))) {
            pendingReplacements.remove(senderKey);
        }
    }

    private static TimedPercentage percentage(String rawPercent, String rawDuration, double maximum, String name) {
        if (rawPercent.equalsIgnoreCase("off") || rawPercent.equals("0")) return TimedPercentage.none();
        double percent = positiveDecimal(rawPercent, name + " percentage");
        if (percent > maximum) throw new IllegalArgumentException(name + " percentage cannot exceed " + maximum + ".");
        long duration = durationMillis(rawDuration);
        long expiresAt;
        try {
            expiresAt = duration == 0 ? 0 : Math.addExact(System.currentTimeMillis(), duration);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Duration is too large.");
        }
        return new TimedPercentage(percent, expiresAt);
    }

    private static long durationMillis(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        if (List.of("permanent", "forever", "infinite", "until-off", "0").contains(value)) return 0;
        if (!value.matches("[1-9][0-9]*(s|m|h|d|w)")) {
            throw new IllegalArgumentException("Duration must look like 30m, 2h, 7d or permanent.");
        }
        long amount;
        try { amount = Long.parseLong(value.substring(0, value.length() - 1)); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("Duration is too large."); }
        long multiplier = switch (value.charAt(value.length() - 1)) {
            case 's' -> 1_000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            case 'w' -> 604_800_000L;
            default -> throw new IllegalArgumentException("Unsupported duration unit.");
        };
        try { return Math.multiplyExact(amount, multiplier); }
        catch (ArithmeticException exception) { throw new IllegalArgumentException("Duration is too large."); }
    }

    private static void require(String[] args, int count, String usage) {
        if (args.length < count) throw new IllegalArgumentException("Usage: " + usage);
    }

    private static int integer(String value, String name) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid " + name + ": " + value); }
    }

    private static int integerOrMinusOne(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { return -1; }
    }

    private static double decimal(String value, String name) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
    }

    private static double positiveDecimal(String value, String name) {
        double parsed = decimal(value, name);
        if (parsed <= 0) throw new IllegalArgumentException(name + " must be positive.");
        return parsed;
    }

    private static boolean bool(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on")) return true;
        if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off")) return false;
        throw new IllegalArgumentException("Value must be true/on or false/off.");
    }

    private static String join(String[] args, int start) {
        return String.join(" ", List.of(args).subList(start, args.length));
    }

    private static String round(double value) { return String.format(Locale.ROOT, "%.2f", value); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("addonsnpc.admin")) return List.of();
        if (args.length == 1) return filter(List.of("help", "create", "delete", "cleanup", "list", "info", "move", "tp",
                "enable", "disable", "name", "description", "type", "skin", "equipment", "rotation", "message", "action", "set", "shop", "buyer", "guildtrader", "reload"), args[0]);
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("cleanup") && args.length == 2) {
            List<String> targets = new ArrayList<>();
            targets.add("orphans");
            targets.addAll(manager.loadedOrphanIds());
            return filter(targets, args[1]);
        }
        if (args.length == 2 && List.of("delete", "info", "move", "tp", "enable", "disable", "name",
                "description", "type", "skin", "equipment", "rotation", "set").contains(root)) return filter(npcs.ids(), args[1]);

        if (root.equals("type") && args.length == 3) return filter(entityTypeNames(), args[2]);
        if (root.equals("skin")) {
            if (args.length == 3) return filter(List.of("player", "url", "clear"), args[2]);
            if (args.length == 4 && args[2].equalsIgnoreCase("player")) return filter(onlinePlayerNames(), args[3]);
            if (args.length == 4 && args[2].equalsIgnoreCase("url"))
                return filter(List.of("https://textures.minecraft.net/texture/"), args[3]);
        }
        if (root.equals("equipment")) {
            if (args.length == 3) return filter(List.of("right", "left"), args[2]);
            if (args.length == 4) {
                List<String> items = new ArrayList<>(List.of("hand", "clear"));
                items.addAll(materialNames());
                return filter(items, args[3]);
            }
        }
        if (root.equals("rotation") && args.length == 3) return filter(List.of("set", "reset"), args[2]);

        if ((root.equals("message") || root.equals("dialogue")) && args.length == 2)
            return filter(List.of("add", "remove", "clear", "mode", "list"), args[1]);
        if (root.equals("action") && args.length == 2) return filter(List.of("add", "remove", "clear", "list"), args[1]);
        if ((root.equals("message") || root.equals("dialogue") || root.equals("action")) && args.length == 3)
            return filter(npcs.ids(), args[2]);
        if ((root.equals("message") || root.equals("dialogue")) && args.length == 4) {
            NpcDefinition npc = npcs.get(args[2]);
            if (args[1].equalsIgnoreCase("mode")) return filter(List.of("all", "random", "sequential"), args[3]);
            if (args[1].equalsIgnoreCase("remove") && npc != null)
                return filter(indexes(npc.messages().size()), args[3]);
            if (args[1].equalsIgnoreCase("add"))
                return filter(List.of("&eПривет,{player}!", "&7Нажмите_ещё_раз...", "{npc}"), args[3]);
        }
        if (root.equals("action") && args.length == 4 && args[1].equalsIgnoreCase("add"))
            return filter(List.of("message", "console", "player", "shop", "buyer", "guildtrader", "sound", "title"), args[3]);
        if (root.equals("action") && args.length == 4 && args[1].equalsIgnoreCase("remove")) {
            NpcDefinition npc = npcs.get(args[2]);
            if (npc != null) return filter(indexes(npc.actions().size()), args[3]);
        }
        if (root.equals("action") && args.length == 5 && args[1].equalsIgnoreCase("add")) {
            return switch (args[3].toLowerCase(Locale.ROOT)) {
                case "shop" -> filter(shops.ids(), args[4]);
                case "buyer" -> filter(buyers.ids(), args[4]);
                case "guildtrader", "guildshop" -> filter(guildTraders.ids(), args[4]);
                case "sound" -> filter(List.of("entity.villager.yes|1|1", "entity.player.levelup|1|1",
                        "block.note_block.pling|1|1", "entity.enderman.teleport|1|1"), args[4]);
                case "title" -> filter(List.of("&eЗаголовок|&7Подзаголовок"), args[4]);
                case "message" -> filter(List.of("&eСообщение_для_{player}"), args[4]);
                default -> List.of();
            };
        }

        if (root.equals("set")) {
            if (args.length == 3)
                return filter(List.of("cooldown", "permission", "click", "look", "lookrange", "lookmode", "visibility", "namerange", "pose"), args[2]);
            if (args.length == 4) {
                return switch (args[2].toLowerCase(Locale.ROOT)) {
                    case "cooldown" -> filter(List.of("0", "0.5", "1", "2", "5", "10"), args[3]);
                    case "permission" -> filter(permissionNames(), args[3]);
                    case "click" -> filter(List.of("RIGHT", "LEFT", "BOTH"), args[3]);
                    case "look" -> filter(List.of("on", "off"), args[3]);
                    case "lookrange" -> filter(List.of("4", "8", "12", "16", "32"), args[3]);
                    case "lookmode" -> filter(List.of("HEAD", "BODY"), args[3]);
                    case "visibility" -> filter(List.of("16", "24", "32", "48", "64", "96"), args[3]);
                    case "namerange" -> filter(List.of("8", "12", "16", "24", "32", "48"), args[3]);
                    case "pose" -> filter(poseNames(), args[3]);
                    default -> List.of();
                };
            }
        }

        if (root.equals("shop")) {
            if (args.length == 2)
                return filter(List.of("create", "delete", "list", "open", "offer", "quantity", "discount", "remove"), args[1]);
            String operation = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
            if (args.length == 3 && List.of("delete", "open", "offer", "quantity", "discount", "remove").contains(operation))
                return filter(shops.ids(), args[2]);
            ShopDefinition shop = args.length > 2 ? shops.get(args[2]) : null;
            if (args.length == 4 && shop != null) {
                if (operation.equals("offer")) return filter(allSlots(shop.size()), args[3]);
                if (operation.equals("remove") || operation.equals("quantity")) return filter(slots(shop, true), args[3]);
                if (operation.equals("discount")) {
                    List<String> targets = new ArrayList<>(); targets.add("all"); targets.addAll(slots(shop, true));
                    return filter(targets, args[3]);
                }
            }
            if (operation.equals("offer")) {
                if (args.length == 5) return filter(List.of("0", "1", "5", "10", "25", "100"), args[4]);
                if (args.length == 6) return filter(shopProductNames(), args[5]);
                if (args.length == 7) return filter(List.of("-1", "0", "1", "5", "10", "64", "100"), args[6]);
            }
            if (operation.equals("quantity") && args.length == 5)
                return filter(List.of("1", "2", "4", "8", "16", "32", "64"), args[4]);
            if (operation.equals("discount") && args.length == 5)
                return filter(List.of("off", "5", "10", "15", "25", "50", "100"), args[4]);
            if (operation.equals("discount") && args.length == 6)
                return filter(List.of("permanent", "30m", "1h", "6h", "1d", "7d"), args[5]);
        }
        if (root.equals("buyer")) {
            if (args.length == 2)
                return filter(List.of("create", "delete", "list", "open", "title", "size", "offer", "price", "bulk", "bonus", "match", "name", "lore", "command", "permission", "remove"), args[1]);
            String operation = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
            if (args.length == 3 && List.of("delete", "open", "title", "size", "offer", "price", "bulk", "bonus", "match", "name", "lore", "command", "permission", "remove").contains(operation))
                return filter(buyers.ids(), args[2]);
            BuyerDefinition buyer = args.length > 2 ? buyers.get(args[2]) : null;
            if (args.length == 4 && buyer != null) {
                if (operation.equals("offer")) return filter(allSlots(buyer.size()), args[3]);
                if (operation.equals("size")) return filter(List.of("9", "18", "27", "36", "45", "54"), args[3]);
                if (operation.equals("bonus")) {
                    List<String> targets = new ArrayList<>(); targets.add("all"); targets.addAll(buyerSlots(buyer, true));
                    return filter(targets, args[3]);
                }
                if (List.of("price", "bulk", "match", "name", "lore", "command", "permission", "remove").contains(operation))
                    return filter(buyerSlots(buyer, true), args[3]);
            }
            if (operation.equals("offer")) {
                if (args.length == 5) return filter(List.of("0.1", "1", "5", "10", "25", "100"), args[4]);
                if (args.length == 6) {
                    List<String> items = new ArrayList<>(); items.add("hand"); items.addAll(materialNames());
                    return filter(items, args[5]);
                }
                if (args.length == 7) return filter(List.of("off", "0", "16", "32", "64", "128"), args[6]);
                if (args.length == 8 && (args[6].equalsIgnoreCase("off") || args[6].equals("0")))
                    return filter(List.of("material", "exact"), args[7]);
                if (args.length == 8) return filter(List.of("1", "10", "50", "100", "500"), args[7]);
                if (args.length == 9) return filter(List.of("material", "exact"), args[8]);
            }
            if (operation.equals("bulk")) {
                if (args.length == 5) return filter(List.of("off", "16", "32", "64", "128"), args[4]);
                if (args.length == 6) return filter(List.of("10", "50", "100", "500"), args[5]);
            }
            if (operation.equals("price") && args.length == 5)
                return filter(List.of("0.1", "1", "5", "10", "25", "100"), args[4]);
            if (operation.equals("match") && args.length == 5)
                return filter(List.of("material", "exact"), args[4]);
            if (operation.equals("permission") && args.length == 5)
                return filter(permissionNames(), args[4]);
            if (operation.equals("bonus") && args.length == 5)
                return filter(List.of("off", "5", "10", "15", "25", "50", "100", "200"), args[4]);
            if (operation.equals("bonus") && args.length == 6)
                return filter(List.of("permanent", "30m", "1h", "6h", "1d", "7d"), args[5]);
            if ((operation.equals("lore") || operation.equals("command")) && args.length == 5)
                return filter(List.of("add", "remove", "clear", "list"), args[4]);
            if ((operation.equals("lore") || operation.equals("command")) && args.length == 6
                    && args[4].equalsIgnoreCase("remove") && buyer != null) {
                BuyerOffer offer = buyer.offers().get(integerOrMinusOne(args[3]));
                if (offer != null) return filter(indexes(operation.equals("lore") ? offer.lore().size() : offer.commands().size()), args[5]);
            }
            if (operation.equals("lore") && args.length == 6 && args[4].equalsIgnoreCase("add"))
                return filter(List.of("&7Цена:_{unit_price}", "&7Оптом:_{bulk_amount}_за_{bulk_price}"), args[5]);
            if (operation.equals("command") && args.length == 6 && args[4].equalsIgnoreCase("add"))
                return filter(List.of("say_{player}_продал_{amount}_{item}_за_{price}"), args[5]);
        }
        if (root.equals("guildtrader") || root.equals("guildshop")) {
            if (args.length == 2) return filter(List.of("create", "delete", "list", "open", "title", "size", "rank",
                    "offer", "price", "icon", "name", "lore", "permission", "remove"), args[1]);
            String operation = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
            if (args.length == 3 && List.of("delete", "open", "title", "size", "rank", "offer", "price", "icon",
                    "name", "lore", "permission", "remove").contains(operation)) return filter(guildTraders.ids(), args[2]);
            GuildTraderDefinition trader = args.length > 2 ? guildTraders.get(args[2]) : null;
            if (args.length == 4 && trader != null) {
                if (operation.equals("offer")) return filter(allSlots(trader.size()), args[3]);
                if (operation.equals("size")) return filter(List.of("9", "18", "27", "36", "45", "54"), args[3]);
                if (operation.equals("rank")) return filter(List.of("member", "officer", "leader"), args[3]);
                if (List.of("price", "icon", "name", "lore", "permission", "remove").contains(operation))
                    return filter(guildTraderSlots(trader, true), args[3]);
            }
            if (operation.equals("offer")) {
                if (args.length == 5) return filter(java.util.Arrays.stream(GuildBonusType.values()).map(type -> type.name().toLowerCase(Locale.ROOT)).toList(), args[4]);
                if (args.length == 6) return filter(List.of("1", "1.5", "2", "3"), args[5]);
                if (args.length == 7) return filter(List.of("30m", "1h", "6h", "1d", "7d", "30d", "permanent"), args[6]);
                if (args.length == 8) return filter(List.of("0", "100", "1000", "5000", "25000"), args[7]);
            }
            if (operation.equals("price") && args.length == 5)
                return filter(List.of("0", "100", "1000", "5000", "25000"), args[4]);
            if (operation.equals("icon") && args.length == 5) {
                List<String> icons = new ArrayList<>(); icons.add("auto"); icons.addAll(materialNames()); return filter(icons, args[4]);
            }
            if (operation.equals("permission") && args.length == 5) return filter(permissionNames(), args[4]);
            if (operation.equals("lore") && args.length == 5) return filter(List.of("add", "remove", "clear", "list"), args[4]);
            if (operation.equals("lore") && args.length == 6 && args[4].equalsIgnoreCase("remove") && trader != null) {
                GuildBonusOffer offer = trader.offers().get(integerOrMinusOne(args[3]));
                if (offer != null) return filter(indexes(offer.lore().size()), args[5]);
            }
            if (operation.equals("lore") && args.length == 6 && args[4].equalsIgnoreCase("add"))
                return filter(List.of("&7Бонус:_{bonus_value}", "&7Срок:_{duration}", "&6Цена:_{price}"), args[5]);
        }
        return List.of();
    }

    private List<String> onlinePlayerNames() {
        return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).sorted().toList();
    }

    private List<String> permissionNames() {
        List<String> result = new ArrayList<>();
        result.add("none");
        plugin.getServer().getPluginManager().getPermissions().stream()
                .map(permission -> permission.getName()).sorted().forEach(result::add);
        return result;
    }

    private static List<String> entityTypeNames() {
        List<String> result = new ArrayList<>();
        for (EntityType type : EntityType.values()) {
            if (NpcDefinition.isSupportedEntityType(type)) result.add(type.name());
        }
        return result;
    }

    private static List<String> poseNames() {
        List<String> result = new ArrayList<>();
        for (Pose pose : Pose.values()) result.add(pose.name());
        return result;
    }

    private static List<String> materialNames() {
        List<String> result = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isItem()) result.add(material.name());
        }
        return result;
    }

    private static List<String> shopProductNames() {
        List<String> result = new ArrayList<>();
        result.add("hand");
        result.addAll(materialNames());
        for (String container : List.of("POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW")) {
            for (PotionType type : PotionType.values()) result.add(container + ':' + type.name());
        }
        return result;
    }

    private static String itemName(ItemStack item) {
        return item == null || item.getType().isAir() ? "empty" : item.getType().name();
    }

    private static List<String> slots(ShopDefinition shop, boolean occupiedOnly) {
        List<String> result = new ArrayList<>();
        for (int slot = 0; slot < shop.size(); slot++) {
            boolean occupied = shop.offers().containsKey(slot);
            if (occupiedOnly == occupied) result.add(Integer.toString(slot));
        }
        return result;
    }

    private static List<String> buyerSlots(BuyerDefinition buyer, boolean occupiedOnly) {
        List<String> result = new ArrayList<>();
        for (int slot = 0; slot < buyer.size(); slot++) {
            boolean occupied = buyer.offers().containsKey(slot);
            if (occupiedOnly == occupied) result.add(Integer.toString(slot));
        }
        return result;
    }

    private static List<String> guildTraderSlots(GuildTraderDefinition trader, boolean occupiedOnly) {
        List<String> result = new ArrayList<>();
        for (int slot = 0; slot < trader.size(); slot++) {
            boolean occupied = trader.offers().containsKey(slot);
            if (occupiedOnly == occupied) result.add(Integer.toString(slot));
        }
        return result;
    }

    private static List<String> allSlots(int size) {
        List<String> result = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) result.add(Integer.toString(slot));
        return result;
    }

    private static List<String> indexes(int size) {
        List<String> result = new ArrayList<>();
        for (int index = 1; index <= size; index++) result.add(Integer.toString(index));
        return result;
    }

    private static List<String> filter(Collection<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(value);
        return result;
    }

    private record PendingReplacement(String signature, long expiresAt) {}
}
