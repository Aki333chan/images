package ovh.aurumgg.companion.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ovh.aurumgg.companion.core.model.InventoryInfo;
import ovh.aurumgg.companion.core.model.ItemInfo;
import ovh.aurumgg.companion.core.model.ItemSpec;
import ovh.aurumgg.companion.core.model.PermissionChange;
import ovh.aurumgg.companion.core.model.PermissionsInfo;
import ovh.aurumgg.companion.core.model.PlayerInfo;
import ovh.aurumgg.companion.core.model.PluginInfo;

/** Подставной игровой сервер для тестов HTTP-слоя. */
public final class FakeGameBridge implements GameBridge {

    public static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    public final List<String> messages = new ArrayList<>();
    public final List<String> slotWrites = new ArrayList<>();
    public final List<String> permissionWrites = new ArrayList<>();
    public boolean steveOnline = true;

    /** Что «установлено» на подставном сервере — тесты это меняют. */
    public final List<PluginInfo> plugins = new ArrayList<>(
            List.of(new PluginInfo("AurumCompanion", "0.1.0", true)));

    /** Существующие группы: по ним проверяется отказ на несуществующую. */
    public final List<String> knownGroups = new ArrayList<>(List.of("default", "vip"));

    @Override
    public List<PlayerInfo> onlinePlayers() {
        if (!steveOnline) return List.of();
        return List.of(new PlayerInfo(STEVE, "Steve", 18.5, 20.0, "world", 100.256, 64.0, -200.5, 42));
    }

    @Override
    public Optional<InventoryInfo> inventory(UUID playerUuid) {
        if (!steveOnline || !playerUuid.equals(STEVE)) return Optional.empty();
        ItemInfo sword = new ItemInfo(
                0,
                "minecraft:diamond_sword",
                1,
                "Меч \"Гроза\"",
                Map.of("minecraft:sharpness", 5),
                List.of("Строка описания"));
        ItemInfo stone = new ItemInfo(9, "minecraft:cobblestone", 64, null, Map.of(), List.of());
        ItemInfo helmet = new ItemInfo(3, "minecraft:diamond_helmet", 1, null, Map.of(), List.of());
        ItemInfo shield = new ItemInfo(0, "minecraft:shield", 1, null, Map.of(), List.of());
        return Optional.of(new InventoryInfo(List.of(sword, stone), List.of(helmet), shield));
    }

    @Override
    public boolean setInventorySlot(UUID playerUuid, int slot, ItemSpec spec) {
        if (!steveOnline || !playerUuid.equals(STEVE)) return false;
        if (spec.isClear()) {
            slotWrites.add("clear:" + slot);
            return true;
        }
        if (!spec.id().startsWith("minecraft:")) return false; // неизвестный материал
        slotWrites.add("set:" + slot + ":" + spec.id() + "x" + spec.count());
        return true;
    }

    @Override
    public void sendMessage(UUID playerUuid, String message) {
        messages.add(playerUuid + ":" + message);
    }

    @Override
    public List<PluginInfo> installedPlugins() {
        return List.copyOf(plugins);
    }

    public void install(String name) {
        plugins.add(new PluginInfo(name, "1.0.0", true));
    }

    private boolean has(String name) {
        return plugins.stream().anyMatch(p -> p.name().equalsIgnoreCase(name));
    }

    @Override
    public Optional<PermissionsInfo> permissions(UUID playerUuid) {
        if (!has("LuckPerms")) return Optional.empty();
        return Optional.of(new PermissionsInfo(
                "default",
                List.of("default"),
                List.of(new PermissionsInfo.PermissionEntry("essentials.fly", true))));
    }

    @Override
    public Optional<PermissionChange.Result> applyPermission(UUID playerUuid, PermissionChange change) {
        if (!has("LuckPerms")) return Optional.empty();
        if (change.kind() == PermissionChange.Kind.GROUP && !knownGroups.contains(change.key())) {
            return Optional.of(PermissionChange.Result.rejected("Группа «" + change.key() + "» не существует"));
        }
        permissionWrites.add(
                (change.remove() ? "remove:" : "add:") + change.kind() + ":" + change.key() + "=" + change.value());
        return Optional.of(PermissionChange.Result.ok());
    }

    @Override
    public Optional<InventoryInfo> offlineInventory(UUID playerUuid, String playerName) {
        if (!has("InvSeePlusPlus")) return Optional.empty();
        if (!playerUuid.equals(STEVE)) return Optional.empty();
        ItemInfo stored = new ItemInfo(0, "minecraft:bread", 5, null, Map.of(), List.of());
        return Optional.of(new InventoryInfo(List.of(stored), List.of(), null));
    }
}
