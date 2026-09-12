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
    private final Set<UUID> pendingRequests = new HashSet<>();
    private final Map<UUID, Long> requestTimes = new HashMap<>();
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
        pendingRequests.clear();
        requestTimes.clear();
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin);
    }

    void forget(UUID player) {
        enhanced.remove(player);
        protocols.remove(player);
        lastPayload.remove(player);
        pendingRequests.remove(player);
        requestTimes.remove(player);
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
        if (!authenticated(player) || player.hasMetadata("NPC")) return;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (pendingRequests.contains(uuid) || now - requestTimes.getOrDefault(uuid, 0L) < 200) return;
        requestTimes.put(uuid, now);
        try {
            UiWireProtocol.AdminRequest request = UiWireProtocol.adminRequest(message);
            Provider provider = provider(player, request.scope());
            if (provider == null) {
                sendAdminState(player, request.scope(), false, "error.permission", List.of());
                return;
            }
            boolean action = !request.action().isEmpty();
            if (!action) {
                sendWithSnapshot(player, request, provider, true, "", uuid);
                return;
            }
            Object rawResult = provider.action(player, request.id(), request.action(), request.arguments());
            if (rawResult instanceof java.util.concurrent.CompletionStage<?> future) {
                pendingRequests.add(uuid);
                future.whenComplete((result, failure) -> {
                    if (!plugin.isEnabled()) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        pendingRequests.remove(uuid);
                        if (!player.isOnline() || plugin.getServer().getPlayer(uuid) != player || !authenticated(player)) return;
                        try {
                            Provider current = provider(player, request.scope());
                            String text = failure == null ? String.valueOf(result) : "error.internal";
                            if (current == null) {
                                pendingRequests.remove(uuid);
                                sendAdminState(player, request.scope(), false, "error.permission", List.of());
                                return;
                            }
                            sendWithSnapshot(player, request, current,
                                    failure == null && !text.startsWith("error."), text, uuid);
                        } catch (ReflectiveOperationException | IOException error) {
                            pendingRequests.remove(uuid);
                            plugin.getLogger().log(Level.WARNING, "Could not refresh AurumUI", error);
                        }
                    });
                });
                return;
            }
            String result = String.valueOf(rawResult);
            sendWithSnapshot(player, request, provider, !result.startsWith("error."), result, uuid);
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

    /** Resolve synchronous and database-backed snapshots without ever blocking Paper. */
    private void sendWithSnapshot(Player player, UiWireProtocol.AdminRequest request, Provider provider,
                                  boolean success, String message, UUID uuid)
            throws InvocationTargetException, IllegalAccessException, IOException {
        Object raw = provider.snapshot(player, request.scope());
        if (!(raw instanceof java.util.concurrent.CompletionStage<?> future)) {
            pendingRequests.remove(uuid);
            sendAdminState(player, request.scope(), success, message, Provider.objects(raw));
            return;
        }
        pendingRequests.add(uuid);
        future.whenComplete((value, failure) -> {
            if (!plugin.isEnabled()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                pendingRequests.remove(uuid);
                if (!player.isOnline() || plugin.getServer().getPlayer(uuid) != player || !authenticated(player)) return;
                try {
                    if (provider(player, request.scope()) == null) {
                        sendAdminState(player, request.scope(), false, "error.permission", List.of());
                        return;
                    }
                    sendAdminState(player, request.scope(), failure == null && success,
                            failure == null ? message : "error.internal",
                            failure == null ? Provider.objects(value) : List.of());
                } catch (ReflectiveOperationException | IOException error) {
                    plugin.getLogger().log(Level.WARNING, "Could not finish AurumUI snapshot", error);
                }
            });
        });
    }

    private Provider provider(Player player, String scope) throws ReflectiveOperationException {
        if (scope.matches("(?:guild|party)(?:-(?:members|players|details|bonuses):[0-9]+)?(?:@[0-9]{1,6})?")) {
            Plugin target = plugin.getServer().getPluginManager().getPlugin("AurumGuilds");
            if (target == null || !target.isEnabled() || !authenticated(player)) return null;
            return new Provider(target, target.getClass().getMethod("aurumSocialSnapshot", Player.class, String.class),
                    target.getClass().getMethod("aurumSocialAction", Player.class, String.class, String.class, Map.class));
        }
        // Экономика игрока — не админская вкладка: собственный баланс и перевод
        // доступны всем, как гильдии и пати. Права внутри (aurum.balance,
        // aurum.pay) проверяет сам Core на каждый вызов.
        if (scope.equals("economy") || scope.equals("economy-admin")) {
            if (scope.equals("economy-admin")
                    && !(player.hasPermission("aurumui.admin") && player.hasPermission("aurum.admin.economy"))) {
                return null;
            }
            Plugin core = plugin.getServer().getPluginManager().getPlugin("AurumCore");
            if (core == null || !core.isEnabled() || !authenticated(player)) return null;
            return new Provider(core,
                    core.getClass().getMethod("aurumEconomySnapshot", Player.class, String.class),
                    core.getClass().getMethod("aurumEconomyAction", Player.class, String.class, String.class, Map.class));
        }
        if (scope.equals("trade") || scope.equals("claims-admin")) {
            if (scope.equals("trade") && !player.hasPermission("aurum.trade")) return null;
            if (scope.equals("claims-admin")
                    && !(player.hasPermission("aurumui.admin") && player.hasPermission("aurum.admin.claims"))) {
                return null;
            }
            Plugin core = plugin.getServer().getPluginManager().getPlugin("AurumCore");
            if (core == null || !core.isEnabled() || !authenticated(player)) return null;
            String prefix = scope.equals("trade") ? "aurumTrade" : "aurumClaims";
            return new Provider(core,
                    core.getClass().getMethod(prefix + "Snapshot", Player.class, String.class),
                    core.getClass().getMethod(prefix + "Action", Player.class, String.class, String.class, Map.class));
        }
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
        // Отправляем согласованную версию, а не версию этой сборки: клиент
        // сверяет её со своей и отказывается от чужой, так что более новый
        // номер тихо сломал бы админские вкладки всем, кто ещё не обновился.
        int protocol = protocols.getOrDefault(player.getUniqueId(), UiWireProtocol.VERSION);
        byte[] payload = UiWireProtocol.adminState(protocol, ++revision, scope, success, message, objects);
        if (payload.length > maxPayloadBytes) {
            payload = UiWireProtocol.adminState(protocol, ++revision, scope, false, "error.too_many_objects", List.of());
        }
        player.sendPluginMessage(plugin, ADMIN_STATE_CHANNEL, payload);
    }

    private record Provider(Plugin plugin, Method snapshotMethod, Method actionMethod) {
        @SuppressWarnings("unchecked")
        Object snapshot(Player player, String scope)
                throws InvocationTargetException, IllegalAccessException {
            return snapshotMethod.invoke(plugin, player, scope);
        }

        static List<Map<String, String>> objects(Object raw) {
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

        Object action(Player player, String id, String action, Map<String, String> arguments)
                throws InvocationTargetException, IllegalAccessException {
            Object raw = actionMethod.invoke(plugin, player, id, action, arguments);
            return raw == null ? "" : raw;
        }
    }

    private int capabilities(Player player) {
        int result = 0;
        if (authenticated(player)) {
            try { if (provider(player, "guild") != null) result |= UiWireProtocol.SOCIAL; }
            catch (ReflectiveOperationException ignored) { }
        }
        if (authenticated(player) && hasEconomyProvider()) {
            if (player.hasPermission("aurum.balance")) result |= UiWireProtocol.ECONOMY;
            if (player.hasPermission("aurum.trade") && hasCoreUiProvider("aurumTrade")) {
                result |= UiWireProtocol.TRADE;
            }
            if (player.hasPermission("aurumui.admin") && player.hasPermission("aurum.admin.economy")) {
                result |= UiWireProtocol.ADMIN_ECONOMY;
            }
            if (player.hasPermission("aurumui.admin") && player.hasPermission("aurum.admin.claims")
                    && hasCoreUiProvider("aurumClaims")) {
                result |= UiWireProtocol.ADMIN_CLAIMS;
            }
        }
        if (!player.hasPermission("aurumui.admin")) return result;
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

    private boolean authenticated(Player player) {
        Plugin auth = plugin.getServer().getPluginManager().getPlugin("AurumAuth");
        if (auth == null) return true;
        return auth.isEnabled() && AuthIntegration.provider().map(api -> api.isAuthenticated(player.getUniqueId())).orElse(false);
    }

    /** Есть ли на сервере AurumCore, умеющий отвечать игровому окну. */
    private boolean hasEconomyProvider() {
        Plugin core = plugin.getServer().getPluginManager().getPlugin("AurumCore");
        if (core == null || !core.isEnabled()) return false;
        try {
            core.getClass().getMethod("aurumEconomySnapshot", Player.class, String.class);
            core.getClass().getMethod("aurumEconomyAction", Player.class, String.class, String.class, Map.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private boolean hasCoreUiProvider(String prefix) {
        Plugin core = plugin.getServer().getPluginManager().getPlugin("AurumCore");
        if (core == null || !core.isEnabled()) return false;
        try {
            core.getClass().getMethod(prefix + "Snapshot", Player.class, String.class);
            core.getClass().getMethod(prefix + "Action", Player.class, String.class, String.class, Map.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
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
