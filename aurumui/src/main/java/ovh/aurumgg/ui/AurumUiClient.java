package ovh.aurumgg.ui;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class AurumUiClient implements ClientModInitializer {
    static final String VERSION = "0.7.0";
    private static final UiSettings SETTINGS = UiSettings.load();
    private static volatile List<UiPanel> panels = List.of();
    private static volatile long revision = -1;
    private static volatile int capabilities;
    private static volatile int serverProtocol;
    private static volatile WireProtocol.AdminState adminState = WireProtocol.AdminState.empty();
    private static int helloCooldown;
    private static int stateTimeout;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("aurumui", "main"));
        KeyMapping menu = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.aurumui.settings", InputConstants.Type.KEYSYM, InputConstants.KEY_U, category));

        PayloadTypeRegistry.serverboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AdminRequestPayload.TYPE, AdminRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AdminStatePayload.TYPE, AdminStatePayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE, (payload, context) -> {
            try {
                WireProtocol.State state = WireProtocol.state(payload.data());
                context.client().execute(() -> {
                    if (state.revision() >= revision) {
                        revision = state.revision();
                        capabilities = state.capabilities();
                        serverProtocol = state.protocol();
                        panels = state.panels();
                        stateTimeout = 300;
                    }
                });
            } catch (IOException ignored) {
                // Invalid server payload is ignored; vanilla HUD remains available.
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(AdminStatePayload.TYPE, (payload, context) -> {
            try {
                WireProtocol.AdminState state = WireProtocol.adminState(payload.data());
                context.client().execute(() -> {
                    if (state.revision() >= adminState.revision()) {
                        adminState = state;
                        if (context.client().gui.screen() instanceof AurumSettingsScreen screen) {
                            screen.adminUpdated(state);
                        }
                    }
                });
            } catch (IOException ignored) {
                // Malformed or unsupported response never affects normal gameplay.
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            panels = List.of();
            revision = -1;
            capabilities = 0;
            serverProtocol = 0;
            adminState = WireProtocol.AdminState.empty();
            stateTimeout = 0;
            helloCooldown = 0;
            sendHello();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            panels = List.of();
            revision = -1;
            capabilities = 0;
            serverProtocol = 0;
            adminState = WireProtocol.AdminState.empty();
            stateTimeout = 0;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menu.consumeClick()) {
                client.gui.setScreen(new AurumSettingsScreen(
                        client.gui.screen(), SETTINGS, capabilities, serverProtocol, panels.size(), adminState));
            }
            if (client.getConnection() == null) return;
            if (helloCooldown-- <= 0) {
                sendHello();
                helloCooldown = 200;
            }
            if (stateTimeout > 0 && --stateTimeout == 0) {
                panels = List.of();
                revision = -1;
                capabilities = 0;
                serverProtocol = 0;
            }
        });

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("aurumui", "panels"),
                (graphics, deltaTracker) -> AurumHudRenderer.render(graphics, panels, SETTINGS));
    }

    private static void sendHello() {
        if (ClientPlayNetworking.canSend(HelloPayload.TYPE)) {
            // Сервер отвергает версию новее своей и запоминает наибольшую из
            // принятых, поэтому здороваемся по убыванию. Пропустить ступень
            // нельзя: сервер протокола 3 не понял бы 4 и откатился бы на 2, а
            // вместе с этим потерял бы все админские вкладки.
            ClientPlayNetworking.send(new HelloPayload(WireProtocol.hello(VERSION)));
            ClientPlayNetworking.send(new HelloPayload(WireProtocol.hello((short) 3, VERSION)));
            ClientPlayNetworking.send(new HelloPayload(WireProtocol.hello((short) 2, VERSION)));
            ClientPlayNetworking.send(new HelloPayload(WireProtocol.hello((short) 1, VERSION)));
        }
    }

    static void requestAdmin(String scope) {
        if (serverProtocol < 3 || !ClientPlayNetworking.canSend(AdminRequestPayload.TYPE)) return;
        ClientPlayNetworking.send(new AdminRequestPayload(WireProtocol.adminList(scope)));
    }

    static void adminAction(String scope, String id, String action, Map<String, String> arguments) {
        if (serverProtocol < 3 || !ClientPlayNetworking.canSend(AdminRequestPayload.TYPE)) return;
        ClientPlayNetworking.send(new AdminRequestPayload(WireProtocol.adminAction(scope, id, action, arguments)));
    }
}
