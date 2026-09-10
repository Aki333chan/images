package ovh.aurumgg.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** List-backed HUD settings and typed administration for configured Aurum objects. */
final class AurumSettingsScreen extends Screen {
    private static final int GAP = 6;
    private final Screen parent;
    private final UiSettings settings;
    private final int capabilities;
    private final int serverProtocol;
    private final int activePanels;
    private WireProtocol.AdminState adminState;
    private Tab tab = Tab.HUD;
    private String npcScope = "npc";
    private String socialScope = "guild";
    private String requestedScope = "";
    private long requestedAt;
    private String selectedId = "";
    private int listPage;
    private int actionPage;
    private Runnable repeatSocialAction;
    private boolean partyDisband;

    AurumSettingsScreen(Screen parent, UiSettings settings, int capabilities, int serverProtocol,
                        int activePanels, WireProtocol.AdminState adminState) {
        super(Component.translatable("screen.aurumui.title"));
        this.parent = parent;
        this.settings = settings;
        this.capabilities = capabilities;
        this.serverProtocol = serverProtocol;
        this.activePanels = activePanels;
        this.adminState = adminState;
    }

    @Override protected void init() {
        addTabs();
        if (tab == Tab.HUD) addHudSettings();
        else addAdminView();
    }

    private void addTabs() {
        List<Tab> available = new ArrayList<>(List.of(Tab.HUD));
        if (has(WireProtocol.SOCIAL)) { available.add(Tab.GUILD); available.add(Tab.PARTY); }
        if (has(WireProtocol.ADMIN_ARENA)) available.add(Tab.ARENA);
        if (has(WireProtocol.ADMIN_NPC)) available.add(Tab.NPC);
        if (has(WireProtocol.ADMIN_SLOTS)) available.add(Tab.SLOTS);
        if (!available.contains(tab)) tab = Tab.HUD;
        int each = Math.min(92, (contentWidth() - GAP * (available.size() - 1)) / available.size());
        int total = each * available.size() + GAP * (available.size() - 1);
        int x = (width - total) / 2;
        for (Tab value : available) {
            Button button = Button.builder(Component.translatable(value.translation), ignored -> select(value))
                    .bounds(x, tabsY(), each, 20).build();
            button.active = value != tab;
            addRenderableWidget(button);
            x += each + GAP;
        }
    }

    private void addHudSettings() {
        int contentWidth = Math.min(276, width - 20);
        int left = (width - contentWidth) / 2;
        int y = contentTop();
        int each = (contentWidth - GAP) / 2;
        addBoolean(left, y, each, "screen.aurumui.enabled", settings.enabled, value -> settings.enabled = value);
        addBoolean(left + each + GAP, y, each, "screen.aurumui.arena", settings.arena, value -> settings.arena = value);
        addBoolean(left, y + 24, each, "screen.aurumui.party", settings.party, value -> settings.party = value);
        addBoolean(left + each + GAP, y + 24, each, "screen.aurumui.guilds", settings.guilds, value -> settings.guilds = value);
        addRenderableWidget(CycleButton.builder(value -> Component.translatable(value.translation), settings.scale)
                .withValues(List.of(UiSettings.Scale.COMPACT, UiSettings.Scale.NORMAL, UiSettings.Scale.LARGE))
                .create(left, y + 48, contentWidth, 20, Component.translatable("screen.aurumui.scale"),
                        (button, value) -> { settings.scale = value; settings.save(); }));
        addRenderableWidget(CycleButton.builder(value -> Component.translatable(value.translation), settings.opacity)
                .withValues(List.of(UiSettings.Opacity.LIGHT, UiSettings.Opacity.NORMAL, UiSettings.Opacity.DARK))
                .create(left, y + 72, contentWidth, 20, Component.translatable("screen.aurumui.opacity"),
                        (button, value) -> { settings.opacity = value; settings.save(); }));
        int footer = height - 26;
        addRenderableWidget(Button.builder(Component.translatable("screen.aurumui.reset"), button -> {
            settings.reset(); rebuildWidgets();
        }).bounds(left, footer, (contentWidth - GAP) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(left + (contentWidth + GAP) / 2, footer, (contentWidth - GAP) / 2, 20).build());
    }

    private void addAdminView() {
        int top = contentTop();
        if (tab == Tab.NPC && !npcScope.contains("-offer:")) {
            addScopeButton("npc", "screen.aurumui.scope.npcs", top);
            addScopeButton("shop", "screen.aurumui.scope.shops", top);
            addScopeButton("buyer", "screen.aurumui.scope.buyers", top);
            top += 26;
        } else if (tab == Tab.NPC && npcScope.contains("-offer:")) {
            addRenderableWidget(Button.builder(Component.translatable("screen.aurumui.action.back"), ignored -> {
                npcScope = npcScope.startsWith("shop-") ? "shop" : "buyer";
                changeScope();
            }).bounds(left(), top, 126, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("screen.aurumui.action.addoffer"), ignored -> addOffer())
                    .bounds(left() + 134, top, contentWidth() - 134, 20).build());
            top += 26;
        }
        if (social()) {
            addRenderableWidget(Button.builder(Component.translatable("screen.aurumui.action.back"), ignored -> {
                socialScope = tab == Tab.GUILD ? "guild" : "party"; changeScope();
            }).bounds(left(), top, contentWidth(), 20).build());
            top += 26;
        }
        String scope = scope();
        if (serverProtocol < 3) { addDoneButton(); return; }
        if (!scope.equals(requestedScope)) {
            requestedScope = scope;
            requestedAt = System.currentTimeMillis();
            AurumUiClient.requestAdmin(scope);
        }
        List<WireProtocol.AdminObject> objects = scope.equals(adminState.scope()) ? adminState.objects() : List.of();
        if (!selectedId.isBlank() && objects.stream().noneMatch(object -> object.id().equals(selectedId))) selectedId = "";
        if (selectedId.isBlank() && !objects.isEmpty()) selectedId = objects.getFirst().id();
        int footer = height - 26;
        int rows = Math.max(1, (footer - top - 30) / 24);
        int maxPage = Math.max(0, (objects.size() - 1) / rows);
        listPage = Math.min(listPage, maxPage);
        int start = listPage * rows;
        for (int row = 0; row < rows && start + row < objects.size(); row++) {
            WireProtocol.AdminObject object = objects.get(start + row);
            Button button = Button.builder(Component.literal(clean(object.title())), ignored -> {
                selectedId = object.id(); actionPage = 0; rebuildWidgets();
            }).bounds(left(), top + row * 24, 126, 20).build();
            button.active = !object.id().equals(selectedId);
            addRenderableWidget(button);
        }
        int pagerY = Math.min(footer - 22, top + rows * 24);
        if (maxPage > 0) {
            Button previous = Button.builder(Component.literal("‹"), ignored -> { listPage--; rebuildWidgets(); })
                    .bounds(left(), pagerY, 38, 20).build();
            previous.active = listPage > 0; addRenderableWidget(previous);
            Button page = Button.builder(Component.literal((listPage + 1) + "/" + (maxPage + 1)), ignored -> {})
                    .bounds(left() + 44, pagerY, 38, 20).build();
            page.active = false; addRenderableWidget(page);
            Button next = Button.builder(Component.literal("›"), ignored -> { listPage++; rebuildWidgets(); })
                    .bounds(left() + 88, pagerY, 38, 20).build();
            next.active = listPage < maxPage; addRenderableWidget(next);
        }
        int objectTop = top;
        objects.stream().filter(object -> object.id().equals(selectedId)).findFirst()
                .ifPresent(object -> addObjectActions(object, objectTop));
        addDoneButton();
    }

    private void addScopeButton(String scope, String key, int y) {
        int index = switch (scope) { case "npc" -> 0; case "shop" -> 1; default -> 2; };
        int each = (contentWidth() - GAP * 2) / 3;
        Button button = Button.builder(Component.translatable(key), ignored -> { npcScope = scope; changeScope(); })
                .bounds(left() + index * (each + GAP), y, each, 20).build();
        button.active = !npcScope.equals(scope);
        addRenderableWidget(button);
    }

    private void addObjectActions(WireProtocol.AdminObject object, int top) {
        List<UiAction> actions = actions(object);
        int x = left() + 134;
        int availableWidth = contentWidth() - 134;
        int footer = height - 26;
        int actionTop = top + 29;
        int rows = Math.max(1, (footer - actionTop - 26) / 24);
        int perPage = rows * 2;
        int pages = Math.max(1, (actions.size() + perPage - 1) / perPage);
        actionPage = Math.min(actionPage, pages - 1);
        int start = actionPage * perPage;
        int each = (availableWidth - GAP) / 2;
        for (int index = 0; index < perPage && start + index < actions.size(); index++) {
            UiAction action = actions.get(start + index);
            int column = index % 2;
            int row = index / 2;
            addRenderableWidget(Button.builder(action.label, ignored -> action.run.run())
                    .bounds(x + column * (each + GAP), actionTop + row * 24, each, 20).build());
        }
        if (pages > 1) {
            int y = Math.min(footer - 22, actionTop + rows * 24);
            Button previous = Button.builder(Component.literal("‹"), ignored -> { actionPage--; rebuildWidgets(); })
                    .bounds(x, y, 36, 20).build(); previous.active = actionPage > 0; addRenderableWidget(previous);
            Button page = Button.builder(Component.literal((actionPage + 1) + "/" + pages), ignored -> {})
                    .bounds(x + 42, y, 52, 20).build(); page.active = false; addRenderableWidget(page);
            Button next = Button.builder(Component.literal("›"), ignored -> { actionPage++; rebuildWidgets(); })
                    .bounds(x + 100, y, 36, 20).build(); next.active = actionPage < pages - 1; addRenderableWidget(next);
        }
    }

    private List<UiAction> actions(WireProtocol.AdminObject object) {
        List<UiAction> result = new ArrayList<>();
        switch (object.kind()) {
            case "arena" -> arenaActions(result, object);
            case "slots" -> slotsActions(result, object);
            case "npc" -> npcActions(result, object);
            case "shop" -> shopActions(result, object);
            case "buyer" -> buyerActions(result, object);
            case "shopOffer" -> shopOfferActions(result, object);
            case "buyerOffer" -> buyerOfferActions(result, object);
            case "social" -> socialActions(result, object);
            default -> { }
        }
        return result;
    }

    private boolean social() { return tab == Tab.GUILD || tab == Tab.PARTY; }

    private void socialActions(List<UiAction> list, WireProtocol.AdminObject object) {
        for (String action : object.get("actions").split(",")) {
            if (action.isBlank()) continue;
            action(list, "screen.aurumui.social." + action, () -> {
                String group = object.id().contains(":") ? object.id().split(":")[1] : "";
                switch (action) {
                    case "guild_members", "guild_players", "party_members", "party_players", "guild_details", "guild_bonuses" -> {
                        socialScope = action.replace('_', '-') + ":" + group; changeScope();
                    }
                    case "social_page" -> { socialScope = object.get("scope"); changeScope(); }
                    case "guild_create" -> form(object, action, List.of(
                            new AurumFormScreen.Field("name", "screen.aurumui.field.name", "", 32),
                            new AurumFormScreen.Field("tag", "screen.aurumui.social.tag", "", 16)));
                    case "guild_deposit", "guild_withdraw" -> form(object, action, List.of(
                            new AurumFormScreen.Field("amount", "screen.aurumui.social.amount", "", 24)));
                    case "guild_tag" -> form(object, action, List.of(
                            new AurumFormScreen.Field("tag", "screen.aurumui.social.tag", object.get("tag"), 16)));
                    case "admin_bonus_grant" -> form(object, action, List.of(
                            new AurumFormScreen.Field("magnitude", "screen.aurumui.social.magnitude", object.get("magnitude"), 16),
                            new AurumFormScreen.Field("duration", "screen.aurumui.field.duration", "1h", 16)));
                    case "guild_settings" -> form(object, action, List.of(
                            new AurumFormScreen.Field("friendlyFire", "screen.aurumui.social.friendlyFire", object.get("friendlyFire"), 5),
                            new AurumFormScreen.Field("joinPolicy", "screen.aurumui.social.joinPolicy", object.get("joinPolicy"), 10),
                            new AurumFormScreen.Field("bankAccess", "screen.aurumui.social.bankAccess", object.get("bankAccess"), 24),
                            new AurumFormScreen.Field("motd", "screen.aurumui.social.motd", object.get("motd"), 190)));
                    case "guild_kick", "guild_transfer", "party_kick", "party_promote", "guild_leave", "party_leave",
                         "admin_guild_remove", "admin_guild_transfer", "admin_party_remove", "admin_bonus_revoke" ->
                            minecraft.gui.setScreen(new AurumConfirmScreen(this,
                                    Component.translatable("screen.aurumui.social.confirmTarget", clean(object.title())), () -> send(object, action)));
                    // Server requires a second identical request for destructive disband operations.
                    default -> {
                        repeatSocialAction = () -> send(object, action);
                        partyDisband = action.equals("admin_party_disband");
                        send(object, action);
                    }
                }
            });
        }
    }

    private void arenaActions(List<UiAction> list, WireProtocol.AdminObject o) {
        action(list, "screen.aurumui.action.teleport", () -> send(o, "teleport"));
        action(list, setting("screen.aurumui.field.pool", o.get("finalPool")), () -> input(o, "set_final_pool", "finalPool"));
        action(list, setting("screen.aurumui.field.radius", o.get("radius")), () -> input(o, "set_radius", "radius"));
        action(list, setting("screen.aurumui.field.maxplayers", o.get("maxPlayers")), () -> input(o, "set_max_players", "maxPlayers"));
        toggle(list, "screen.aurumui.field.auto", o, "automatic", "toggle_auto");
        toggle(list, "screen.aurumui.field.betting", o, "betting", "toggle_betting");
        toggle(list, "screen.aurumui.field.kit", o, "kit", "toggle_kit");
        toggle(list, "screen.aurumui.field.finalmode", o, "finalMode", "toggle_final");
        toggle(list, "screen.aurumui.field.friendly", o, "friendlyFire", "toggle_friendly_fire");
        action(list, setting("screen.aurumui.field.bar", o.get("showBar")), () -> send(o, "cycle_show_bar"));
        action(list, setting("screen.aurumui.field.xpmode", o.get("xpMode")), () -> send(o, "cycle_xp_mode"));
        action(list, setting("screen.aurumui.field.winnerxp", o.get("winnerXp")), () -> input(o, "set_winner_xp", "winnerXp"));
        action(list, setting("screen.aurumui.field.finalxp", o.get("finalWinnerXp")), () -> input(o, "set_final_winner_xp", "finalWinnerXp"));
        action(list, "screen.aurumui.action.start", () -> send(o, "start"));
        action(list, "screen.aurumui.action.stop", () -> confirm("screen.aurumui.confirm.stop", () -> send(o, "stop")));
        action(list, "screen.aurumui.action.validate", () -> send(o, "validate"));
        action(list, "screen.aurumui.action.odds", () -> send(o, "odds"));
    }

    private void slotsActions(List<UiAction> list, WireProtocol.AdminObject o) {
        action(list, "screen.aurumui.action.teleport", () -> send(o, "teleport"));
        action(list, setting("screen.aurumui.field.bet", o.get("bet")), () -> input(o, "set_bet", "bet"));
    }

    private void npcActions(List<UiAction> list, WireProtocol.AdminObject o) {
        action(list, "screen.aurumui.action.teleport", () -> send(o, "npc_teleport"));
        action(list, "screen.aurumui.action.movehere", () -> send(o, "npc_move_here"));
        toggle(list, "screen.aurumui.field.enabled", o, "enabled", "npc_toggle_enabled");
        toggle(list, "screen.aurumui.field.look", o, "look", "npc_toggle_look");
        action(list, setting("screen.aurumui.field.lookmode", o.get("lookMode")), () -> send(o, "npc_cycle_look_mode"));
        action(list, "screen.aurumui.action.rotationhere", () -> send(o, "npc_rotation_here"));
        action(list, "screen.aurumui.action.rotationreset", () -> send(o, "npc_rotation_reset"));
        action(list, setting("screen.aurumui.field.lookrange", o.get("lookRange")), () -> input(o, "npc_set_look_range", "lookRange"));
        action(list, setting("screen.aurumui.field.visibility", o.get("visibility")), () -> input(o, "npc_set_visibility", "visibility"));
        action(list, setting("screen.aurumui.field.namerange", o.get("nameRange")), () -> input(o, "npc_set_name_range", "nameRange"));
        action(list, setting("screen.aurumui.field.name", clean(o.title())), () -> input(o, "npc_set_name", "title"));
        action(list, setting("screen.aurumui.field.description", clean(o.get("description"))), () -> input(o, "npc_set_description", "description"));
        action(list, setting("screen.aurumui.field.cooldown", o.get("cooldown")), () -> input(o, "npc_set_cooldown", "cooldown"));
        action(list, setting("screen.aurumui.field.permission", o.get("permission").isBlank() ? "—" : o.get("permission")),
                () -> input(o, "npc_set_permission", "permission"));
        action(list, setting("screen.aurumui.field.type", o.get("entityType")), () -> input(o, "npc_set_entity_type", "entityType"));
        action(list, setting("screen.aurumui.field.pose", o.get("pose")), () -> input(o, "npc_set_pose", "pose"));
        action(list, setting("screen.aurumui.field.click", o.get("clickMode")), () -> send(o, "npc_cycle_click_mode"));
        action(list, setting("screen.aurumui.field.dialogue", o.get("dialogueMode")), () -> send(o, "npc_cycle_dialogue_mode"));
        action(list, "screen.aurumui.action.righthand", () -> send(o, "npc_right_hand"));
        action(list, "screen.aurumui.action.lefthand", () -> send(o, "npc_left_hand"));
        action(list, "screen.aurumui.action.clearright", () -> send(o, "npc_clear_right_hand"));
        action(list, "screen.aurumui.action.clearleft", () -> send(o, "npc_clear_left_hand"));
        action(list, "screen.aurumui.action.skin", () -> form(o, "npc_set_skin", List.of(
                new AurumFormScreen.Field("type", "screen.aurumui.field.skintype", o.get("skinType"), 24),
                new AurumFormScreen.Field("value", "screen.aurumui.field.skinvalue", o.get("skinValue"), 512))));
    }

    private void shopActions(List<UiAction> list, WireProtocol.AdminObject o) {
        action(list, "screen.aurumui.action.open", () -> send(o, "shop_open"));
        action(list, "screen.aurumui.action.offers", () -> openOffers("shop-offer:" + o.id()));
        action(list, setting("screen.aurumui.field.title", clean(o.title())), () -> input(o, "shop_set_title", "title"));
        action(list, setting("screen.aurumui.field.discount", o.get("discount")), () -> promotion(o, "shop_set_discount", "discount"));
    }

    private void buyerActions(List<UiAction> list, WireProtocol.AdminObject o) {
        action(list, "screen.aurumui.action.open", () -> send(o, "buyer_open"));
        action(list, "screen.aurumui.action.offers", () -> openOffers("buyer-offer:" + o.id()));
        action(list, setting("screen.aurumui.field.title", clean(o.title())), () -> input(o, "buyer_set_title", "title"));
        action(list, setting("screen.aurumui.field.bonus", o.get("bonus")), () -> promotion(o, "buyer_set_bonus", "bonus"));
    }

    private void shopOfferActions(List<UiAction> list, WireProtocol.AdminObject o) {
        action(list, setting("screen.aurumui.field.price", o.get("price")), () -> input(o, "shop_offer_set_price", "price"));
        action(list, setting("screen.aurumui.field.quantity", o.get("quantity")), () -> input(o, "shop_offer_set_quantity", "quantity"));
        action(list, setting("screen.aurumui.field.stock", o.get("stock")), () -> input(o, "shop_offer_set_stock", "stock"));
        action(list, setting("screen.aurumui.field.discount", o.get("discount")), () -> promotion(o, "shop_offer_set_discount", "discount"));
        action(list, "screen.aurumui.action.itemfromhand", () -> send(o, "shop_offer_item_from_hand"));
        action(list, setting("screen.aurumui.field.name", clean(o.get("name"))), () -> input(o, "shop_offer_set_name", "name"));
        action(list, "screen.aurumui.action.remove", () -> confirm("screen.aurumui.confirm.removeoffer", () -> send(o, "shop_offer_remove")));
    }

    private void buyerOfferActions(List<UiAction> list, WireProtocol.AdminObject o) {
        action(list, setting("screen.aurumui.field.price", o.get("price")), () -> input(o, "buyer_offer_set_price", "price"));
        action(list, setting("screen.aurumui.field.bulk", o.get("bulkAmount") + " / " + o.get("bulkPrice")), () -> form(o, "buyer_offer_set_bulk", List.of(
                new AurumFormScreen.Field("amount", "screen.aurumui.field.bulkamount", o.get("bulkAmount"), 8),
                new AurumFormScreen.Field("price", "screen.aurumui.field.bulkprice", o.get("bulkPrice"), 24))));
        action(list, setting("screen.aurumui.field.match", o.get("match")), () -> send(o, "buyer_offer_toggle_match"));
        action(list, setting("screen.aurumui.field.bonus", o.get("bonus")), () -> promotion(o, "buyer_offer_set_bonus", "bonus"));
        action(list, "screen.aurumui.action.itemfromhand", () -> send(o, "buyer_offer_item_from_hand"));
        action(list, setting("screen.aurumui.field.name", clean(o.get("name"))), () -> input(o, "buyer_offer_set_name", "name"));
        action(list, "screen.aurumui.action.remove", () -> confirm("screen.aurumui.confirm.removeoffer", () -> send(o, "buyer_offer_remove")));
    }

    private void openOffers(String value) { npcScope = value; changeScope(); }
    private void addOffer() {
        boolean shop = npcScope.startsWith("shop-offer:");
        String parentId = npcScope.substring(npcScope.indexOf(':') + 1);
        List<AurumFormScreen.Field> fields = new ArrayList<>();
        fields.add(new AurumFormScreen.Field("slot", "screen.aurumui.field.slot", "0", 3));
        fields.add(new AurumFormScreen.Field("price", "screen.aurumui.field.price", "1", 24));
        if (shop) {
            fields.add(new AurumFormScreen.Field("quantity", "screen.aurumui.field.quantity", "1", 3));
            fields.add(new AurumFormScreen.Field("stock", "screen.aurumui.field.stock", "-1", 12));
        } else {
            fields.add(new AurumFormScreen.Field("bulkAmount", "screen.aurumui.field.bulkamount", "0", 8));
            fields.add(new AurumFormScreen.Field("bulkPrice", "screen.aurumui.field.bulkprice", "0", 24));
            fields.add(new AurumFormScreen.Field("match", "screen.aurumui.field.match", "material", 8));
        }
        minecraft.gui.setScreen(new AurumFormScreen(this, Component.translatable("screen.aurumui.action.addoffer"), fields,
                values -> submitOffer(parentId, shop, values, false)));
    }

    private void submitOffer(String parentId, boolean shop, Map<String, String> values, boolean replace) {
        String slot = values.getOrDefault("slot", "");
        boolean occupied = adminState.objects().stream().anyMatch(object -> object.get("slot").equals(slot));
        if (occupied && !replace) {
            confirm("screen.aurumui.confirm.replaceoffer", () -> submitOffer(parentId, shop, values, true));
            return;
        }
        Map<String, String> arguments = new java.util.LinkedHashMap<>(values);
        arguments.remove("slot");
        arguments.put("replace", String.valueOf(replace));
        AurumUiClient.adminAction(scope(), parentId + ":" + slot,
                shop ? "shop_offer_create_from_hand" : "buyer_offer_create_from_hand", Map.copyOf(arguments));
    }
    private void promotion(WireProtocol.AdminObject object, String action, String field) {
        form(object, action, List.of(
                new AurumFormScreen.Field("percent", "screen.aurumui.field.percent", object.get(field), 16),
                new AurumFormScreen.Field("duration", "screen.aurumui.field.duration", "0", 12)));
    }
    private void input(WireProtocol.AdminObject object, String action, String field) {
        form(object, action, List.of(new AurumFormScreen.Field("value", "screen.aurumui.field." + field.toLowerCase(),
                object.get(field), 512)));
    }
    private void form(WireProtocol.AdminObject object, String action, List<AurumFormScreen.Field> fields) {
        minecraft.gui.setScreen(new AurumFormScreen(this, Component.literal(clean(object.title())), fields,
                values -> AurumUiClient.adminAction(scope(), object.id(), action, values)));
    }
    private void confirm(String key, Runnable accepted) {
        minecraft.gui.setScreen(new AurumConfirmScreen(this, Component.translatable(key), accepted));
    }
    private void send(WireProtocol.AdminObject object, String action) {
        AurumUiClient.adminAction(scope(), object.id(), action, Map.of());
    }
    private void toggle(List<UiAction> list, String key, WireProtocol.AdminObject object, String field, String action) {
        action(list, setting(key, Component.translatable(object.bool(field) ? "options.on" : "options.off").getString()),
                () -> send(object, action));
    }
    private Component setting(String key, String value) {
        return Component.translatable("screen.aurumui.setting", Component.translatable(key), value);
    }
    private void action(List<UiAction> list, String key, Runnable action) { list.add(new UiAction(Component.translatable(key), action)); }
    private void action(List<UiAction> list, Component label, Runnable action) { list.add(new UiAction(label, action)); }
    private void addBoolean(int x, int y, int width, String label, boolean value, Consumer<Boolean> change) {
        addRenderableWidget(CycleButton.onOffBuilder(value).create(x, y, width, 20, Component.translatable(label),
                (button, selected) -> { change.accept(selected); settings.save(); }));
    }
    private void changeScope() { selectedId = ""; requestedScope = ""; listPage = 0; actionPage = 0; rebuildWidgets(); }
    private void select(Tab value) { tab = value; socialScope = value == Tab.PARTY ? "party" : "guild"; changeScope(); }

    void adminUpdated(WireProtocol.AdminState state) {
        adminState = state;
        if (!state.message().isBlank() && minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.translatable("message.aurumui." + state.message()));
        }
        if (state.scope().equals(scope())) rebuildWidgets();
        if (social() && state.scope().equals(scope()) && repeatSocialAction != null
                && (state.message().equals("social.confirm") || state.message().equals("social.confirmSwitch"))) {
            Runnable accepted = repeatSocialAction;
            repeatSocialAction = null;
            confirm(state.message().equals("social.confirm") ? (partyDisband ? "screen.aurumui.social.confirmPartyDisband" : "screen.aurumui.social.confirmDisband")
                    : "screen.aurumui.social.confirmSwitch", accepted);
        }
    }

    private void addDoneButton() {
        int refreshWidth = Math.min(100, (contentWidth() - GAP) / 3);
        addRenderableWidget(Button.builder(Component.translatable("screen.aurumui.action.refresh"), ignored ->
                AurumUiClient.requestAdmin(scope())).bounds(left(), height - 26, refreshWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
                .bounds(left() + refreshWidth + GAP, height - 26, contentWidth() - refreshWidth - GAP, 20).build());
    }
    private boolean has(int capability) { return (capabilities & capability) != 0; }
    private String scope() { return switch (tab) { case GUILD, PARTY -> socialScope; case ARENA -> "arena"; case NPC -> npcScope; case SLOTS -> "slots"; default -> ""; }; }
    private int contentWidth() { return Math.min(430, width - 20); }
    private int left() { return (width - contentWidth()) / 2; }
    private int tabsY() { return height < 220 ? 31 : 42; }
    private int contentTop() { return tabsY() + 26; }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, height < 220 ? 3 : 10, 0xFFFFC85C);
        // Dedicated status row. Its baseline ends well before the tabs at y=42.
        graphics.centeredText(font, connectionStatus(), width / 2, height < 220 ? 16 : 25, statusColor());
        if (tab != Tab.HUD && serverProtocol < 3) {
            graphics.centeredText(font, Component.translatable("screen.aurumui.admin.requires-v3"), width / 2,
                    contentTop() + 30, 0xFFFF7777);
        }
        if (tab != Tab.HUD && serverProtocol >= 3 && scope().equals(adminState.scope()) && adminState.objects().isEmpty()) {
            graphics.centeredText(font, Component.translatable("screen.aurumui.empty"), width / 2,
                    contentTop() + 52, 0xFFAAAAAA);
        }
        if (tab != Tab.HUD && !selectedId.isBlank()) {
            adminState.objects().stream().filter(o -> o.id().equals(selectedId)).findFirst().ifPresent(object -> {
                int x = left() + 134;
                int y = adminObjectsTop();
                graphics.text(font, Component.literal(font.plainSubstrByWidth(clean(object.title()), contentWidth() - 134)), x, y, 0xFFFFC85C, false);
                if (object.kind().equals("social") && object.get("actions").isBlank()) {
                    int lineY = y + 12;
                    for (var line : font.split(Component.literal(summary(object)), contentWidth() - 134)) {
                        if (lineY >= height - 52) break;
                        graphics.text(font, line, x, lineY, 0xFFAAAAAA, false);
                        lineY += 10;
                    }
                } else graphics.text(font, Component.literal(font.plainSubstrByWidth(summary(object), contentWidth() - 134)), x, y + 12, 0xFFAAAAAA, false);
            });
        }
    }

    @Override public void tick() {
        super.tick();
        // Retry a list request dropped while a previous asynchronous action was pending.
        if (social() && !scope().equals(adminState.scope()) && System.currentTimeMillis() - requestedAt > 1500) {
            requestedAt = System.currentTimeMillis();
            AurumUiClient.requestAdmin(scope());
        }
    }

    private String summary(WireProtocol.AdminObject object) {
        return switch (object.kind()) {
            case "social" -> object.get("summary");
            case "arena" -> object.get("state") + " · " + object.get("redPlayers") + ":" + object.get("bluePlayers")
                    + " · " + object.get("redBets") + "/" + object.get("blueBets");
            case "slots" -> object.get("payment") + (object.bool("spinning") ? " · spinning" : "");
            case "npc" -> object.get("entityType") + " · " + object.get("location");
            case "shop", "buyer" -> object.get("offers") + " offers";
            default -> object.get("material") + " · slot " + object.get("slot");
        };
    }
    private int adminObjectsTop() { return contentTop() + (tab == Tab.NPC || social() ? 26 : 0); }
    private Component connectionStatus() {
        if (serverProtocol == 0) return Component.translatable("screen.aurumui.connection.waiting");
        if (serverProtocol == 1) return Component.translatable("screen.aurumui.connection.legacy", activePanels);
        return Component.translatable("screen.aurumui.connection.connected", activePanels);
    }
    private int statusColor() { return serverProtocol == 0 ? 0xFFFF7777 : serverProtocol == 1 ? 0xFFFFC85C : 0xFF77DD88; }
    private static String clean(String value) { return value == null ? "" : value.replaceAll("(?i)[&§][0-9A-FK-ORX]", ""); }
    @Override public void onClose() { settings.save(); minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private enum Tab {
        HUD("screen.aurumui.tab.hud"), ARENA("screen.aurumui.tab.arena"),
        GUILD("screen.aurumui.guilds"), PARTY("screen.aurumui.party"),
        NPC("screen.aurumui.tab.npc"), SLOTS("screen.aurumui.tab.slots");
        final String translation;
        Tab(String translation) { this.translation = translation; }
    }
    private record UiAction(Component label, Runnable run) {}
}
