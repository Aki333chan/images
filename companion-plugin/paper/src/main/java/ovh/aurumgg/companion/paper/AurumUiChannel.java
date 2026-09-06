package ovh.aurumgg.companion.paper;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.companion.core.ui.UiPanel;
import ovh.aurumgg.companion.core.ui.UiWireProtocol;

/**
 * Local Minecraft-protocol endpoint for the optional AurumUI client.
 *
 * <p>This channel is deliberately independent from Companion's HTTP server:
 * it needs neither the panel token nor an open port. Other Aurum plugins mark
 * their current read-only panels with Bukkit metadata. This keeps them fully
 * usable without Companion and avoids a build-time dependency cycle.</p>
 */
final class AurumUiChannel implements PluginMessageListener, CommandExecutor {
    static final String HELLO_CHANNEL = "aurum:hello";
    static final String STATE_CHANNEL = "aurum:state";
    static final String ADMIN_REQUEST_CHANNEL = "aurum:admin_request";
    static final String ADMIN_STATE_CHANNEL = "aurum:admin_state";
    static final String CLIENT_METADATA = "aurumui.client";

    private final AurumCompanionPlugin plugin;
    private final Set<UUID> enhanced = new HashSet<>();
    private final Map<UUID, Integer> protocols = new HashMap<>();
    private final Map<UUID, byte[]> lastPayload = new HashMap<>();
    private final int maxPayloadBytes;
    private final List<String> panelKeys;
    private long revision;

    AurumUiChannel(AurumCompanionPlugin plugin) {
        this.plugin = plugin;
        maxPayloadBytes = Math.max(1024, Math.min(30_000,
                plugin.getConfig().getInt("ui.max-payload-bytes", 24_576)));
        List<String> configuredKeys = plugin.getConfig().getStringList("ui.panel-metadata-keys");
        panelKeys = configuredKeys.isEmpty()
                ? List.of("aurumui.arena", "aurumui.guilds", "aurumui.party")
                : List.copyOf(configuredKeys);
    }

    void start() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, HELLO_CHANNEL, this);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, ADMIN_REQUEST_CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, STATE_CHANNEL);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, ADMIN_STATE_CHANNEL);
        long period = Math.max(2L, plugin.getConfig().getLong("ui.refresh-ticks", 10L));
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshClients, period, period);
    }

    void close() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.removeMetadata(CLIENT_METADATA, plugin);
        }
        enhanced.clear();
        protocols.clear();
        lastPayload.clear();
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin);
    }

    void forget(UUID player) {
        enhanced.remove(player);
        protocols.remove(player);
        lastPayload.remove(player);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (ADMIN_REQUEST_CHANNEL.equals(channel)) {
            handleAdminRequest(player, message);
            return;
        }
        if (!HELLO_CHANNEL.equals(channel)) return;
        int protocol = UiWireProtocol.helloVersion(message);
        if (protocol == 0) return;
        boolean firstHello = enhanced.add(player.getUniqueId());
        protocols.merge(player.getUniqueId(), protocol, Math::max);
        player.setMetadata(CLIENT_METADATA, new FixedMetadataValue(plugin, protocols.get(player.getUniqueId())));
        // Повторный hello — ещё и heartbeat. Сброс сравнения гарантирует один
        // полный снимок, поэтому клиент сам заметит reload Companion.
        lastPayload.remove(player.getUniqueId());
        if (firstHello) {
            plugin.getLogger().fine(() -> "AurumUI client detected: " + player.getName());
        }
    }

    private void refreshClients() {
        enhanced.removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        protocols.keySet().removeIf(uuid -> !enhanced.contains(uuid));
        lastPayload.keySet().removeIf(uuid -> !enhanced.contains(uuid));
        for (UUID uuid : List.copyOf(enhanced)) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null) continue;
            try {
                int protocol = protocols.getOrDefault(uuid, 1);
                byte[] payload = UiWireProtocol.state(protocol, ++revision, capabilities(player), collectPanels(player));
                if (payload.length > maxPayloadBytes) {
                    plugin.getLogger().warning("AurumUI payload for " + player.getName()
                            + " is too large: " + payload.length + " bytes");
                    continue;
                }
                byte[] previous = lastPayload.get(uuid);
                if (previous != null && equalIgnoringRevision(previous, payload)) continue;
                player.sendPluginMessage(plugin, STATE_CHANNEL, payload);
                lastPayload.put(uuid, payload);
            } catch (IOException | RuntimeException error) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not build AurumUI state for " + player.getName(), error);
            }
        }
    }

    private List<UiPanel> collectPanels(Player player) {
        List<UiPanel> result = new ArrayList<>();
        for (String key : panelKeys) {
            for (MetadataValue value : player.getMetadata(key)) {
                if (value.getOwningPlugin() == plugin) continue;
                UiPanel panel = panel(value.value(), key);
                if (panel != null && !panel.lines().isEmpty()) result.add(panel);
            }
        }
        result.sort(Comparator.comparingInt(UiPanel::priority).reversed().thenComparing(UiPanel::id));
        return result;
    }

    private void handleAdminRequest(Player player, byte[] message) {
        if (protocols.getOrDefault(player.getUniqueId(), 0) < 3 || !enhanced.contains(player.getUniqueId())) return;
        try {
            UiWireProtocol.AdminRequest request = UiWireProtocol.adminRequest(message);
            Provider provider = provider(player, request.scope());
            if (provider == null) {
                sendAdminState(player, request.scope(), false, "error.permission", List.of());
                return;
            }
            boolean action = !request.action().isEmpty();
            String result = action
                    ? provider.action(player, request.id(), request.action(), request.arguments())
                    : "";
            List<Map<String, String>> objects = provider.snapshot(player, request.scope());
            sendAdminState(player, request.scope(), !result.startsWith("error."), result, objects);
        } catch (IOException | ReflectiveOperationException | RuntimeException error) {
            Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause() : error;
            plugin.getLogger().log(Level.WARNING, "Could not process AurumUI admin request from " + player.getName(), cause);
            try {
                sendAdminState(player, "", false, "error.internal", List.of());
            } catch (IOException ignored) {
                // The original problem has already been logged.
            }
        }
    }

    private Provider provider(Player player, String scope) throws ReflectiveOperationException {
        String pluginName;
        String permission;
        if (scope.equals("arena")) {
            pluginName = "AurumArena";
            permission = "arena.admin";
        } else if (scope.equals("slots")) {
            pluginName = "AurumSlots";
            permission = "casino.admin";
        } else if (scope.equals("npc") || scope.equals("shop") || scope.equals("buyer")
                || scope.startsWith("shop-offer:") || scope.startsWith("buyer-offer:")) {
            pluginName = "AddonsNPC";
            permission = "addonsnpc.admin";
        } else {
            return null;
        }
        if (!player.hasPermission("aurumui.admin") || !player.hasPermission(permission)) return null;
        Plugin target = plugin.getServer().getPluginManager().getPlugin(pluginName);
        if (target == null || !target.isEnabled()) return null;
        Method snapshot = target.getClass().getMethod("aurumAdminSnapshot", Player.class, String.class);
        Method action = target.getClass().getMethod(
                "aurumAdminAction", Player.class, String.class, String.class, Map.class);
        return new Provider(target, snapshot, action);
    }

    private void sendAdminState(Player player, String scope, boolean success, String message,
                                List<Map<String, String>> objects) throws IOException {
        byte[] payload = UiWireProtocol.adminState(++revision, scope, success, message, objects);
        if (payload.length > maxPayloadBytes) {
            payload = UiWireProtocol.adminState(++revision, scope, false, "error.too_many_objects", List.of());
        }
        player.sendPluginMessage(plugin, ADMIN_STATE_CHANNEL, payload);
    }

    private record Provider(Plugin plugin, Method snapshotMethod, Method actionMethod) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> snapshot(Player player, String scope)
                throws InvocationTargetException, IllegalAccessException {
            Object raw = snapshotMethod.invoke(plugin, player, scope);
            if (!(raw instanceof List<?> list)) return List.of();
            List<Map<String, String>> result = new ArrayList<>();
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> map)) continue;
                Map<String, String> object = new java.util.LinkedHashMap<>();
                map.forEach((key, value) -> object.put(String.valueOf(key), String.valueOf(value)));
                result.add(Map.copyOf(object));
            }
            return List.copyOf(result);
        }

        String action(Player player, String id, String action, Map<String, String> arguments)
                throws InvocationTargetException, IllegalAccessException {
            Object raw = actionMethod.invoke(plugin, player, id, action, arguments);
            return raw == null ? "" : String.valueOf(raw);
        }
    }

    private int capabilities(Player player) {
        if (!player.hasPermission("aurumui.admin")) return 0;
        int result = 0;
        if (hasAdminProvider("AurumArena") && player.hasPermission("arena.admin")) {
            result |= UiWireProtocol.ADMIN_ARENA;
        }
        if (hasAdminProvider("AddonsNPC") && player.hasPermission("addonsnpc.admin")) {
            result |= UiWireProtocol.ADMIN_NPC;
        }
        if (hasAdminProvider("AurumSlots") && player.hasPermission("casino.admin")) {
            result |= UiWireProtocol.ADMIN_SLOTS;
        }
        return result;
    }

    private boolean hasAdminProvider(String name) {
        Plugin target = plugin.getServer().getPluginManager().getPlugin(name);
        if (target == null || !target.isEnabled()) return false;
        try {
            target.getClass().getMethod("aurumAdminSnapshot", Player.class, String.class);
            target.getClass().getMethod("aurumAdminAction", Player.class, String.class, String.class, Map.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static UiPanel panel(Object raw, String fallbackId) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        String id = string(map.get("id"), fallbackId);
        String title = string(map.get("title"), id);
        int priority = number(map.get("priority"), 0);
        Object rawLines = map.get("lines");
        if (!(rawLines instanceof List<?> list)) return null;
        return new UiPanel(id, priority, title, list.stream().map(String::valueOf).toList());
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean equalIgnoringRevision(byte[] first, byte[] second) {
        // magic (4) + protocol (2) + revision (8)
        return first.length == second.length && first.length >= 14
                && Arrays.equals(first, 0, 6, second, 0, 6)
                && Arrays.equals(first, 14, first.length, second, 14, second.length);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("aurumui.admin")) {
            sender.sendMessage(plugin.text("ui.noPermission"));
            return true;
        }
        sender.sendMessage(plugin.text("ui.status", Map.of("clients", String.valueOf(enhanced.size()))));
        sender.sendMessage(plugin.text("ui.protocol", Map.of(
                "protocol", String.valueOf(UiWireProtocol.VERSION),
                "providers", String.valueOf(panelKeys.size()))));
        return true;
    }
}
