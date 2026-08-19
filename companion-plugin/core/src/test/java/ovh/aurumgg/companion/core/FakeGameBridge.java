package ovh.aurumgg.companion.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ovh.aurumgg.companion.core.model.BalanceChange;
import ovh.aurumgg.companion.core.model.BalanceInfo;
import ovh.aurumgg.companion.core.model.EconomySummary;
import ovh.aurumgg.companion.core.model.InventoryInfo;
import ovh.aurumgg.companion.core.model.ItemInfo;
import ovh.aurumgg.companion.core.model.ItemSpec;
import ovh.aurumgg.companion.core.model.PermissionChange;
import ovh.aurumgg.companion.core.model.PermissionsInfo;
import ovh.aurumgg.companion.core.model.PlayerInfo;
import ovh.aurumgg.companion.core.model.PluginInfo;
import ovh.aurumgg.companion.core.model.PluginToggle;

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

    /** Строки, с которыми звали автодополнение — по ним проверяется нормализация. */
    public final List<String> completedLines = new ArrayList<>();

    /** Подставные команды «сервера»: реального Bukkit в тестах нет. */
    private static final List<String> COMMANDS = List.of("gamemode", "give", "heal", "help");

    @Override
    public List<String> completeCommand(String line) {
        completedLines.add(line);
        int space = line.indexOf(' ');
        if (space < 0) {
            List<String> result = new ArrayList<>();
            for (String command : COMMANDS) {
                if (command.startsWith(line)) result.add(command);
            }
            return result;
        }
        // Аргумент: подсказываем игроков онлайн, как это делает настоящий сервер.
        return steveOnline ? List.of("Steve") : List.of();
    }

    @Override
    public List<PluginInfo> installedPlugins() {
        return List.copyOf(plugins);
    }

    public void install(String name) {
        plugins.add(new PluginInfo(name, "1.0.0", true));
    }

    /** Плагины, которые «отказываются» переключаться, — для проверки отказа. */
    public final List<String> stubborn = new ArrayList<>();

    @Override
    public PluginToggle setPluginEnabled(String pluginName, boolean enabled) {
        if (pluginName.equals("AurumCompanion")) {
            return PluginToggle.failed("Нельзя выключить companion-плагин: панель потеряет связь с сервером");
        }
        for (int i = 0; i < plugins.size(); i++) {
            PluginInfo p = plugins.get(i);
            if (!p.name().equals(pluginName)) continue;
            if (stubborn.contains(pluginName)) {
                return PluginToggle.failed("Плагин отказался переключиться: IllegalStateException");
            }
            plugins.set(i, new PluginInfo(p.name(), p.version(), enabled));
            return PluginToggle.ok(enabled);
        }
        return PluginToggle.failed("Плагин «" + pluginName + "» на сервере не найден");
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

    // ---------- Экономика ----------
    //
    // Подставная экономика включается установкой Vault (install("Vault")) и,
    // отдельно, флагом economyProvider: у реального сервера бывает Vault без
    // единого плагина экономики за ним, и HTTP-слой обязан различать эти два
    // случая разными кодами ошибки.

    public boolean economyProvider = true;

    public final Map<UUID, Double> balances = new LinkedHashMap<>(Map.of(STEVE, 250.0));

    public final Map<UUID, String> playerNames = new LinkedHashMap<>(Map.of(STEVE, "Steve"));

    private boolean economyAvailable() {
        return has("Vault") && economyProvider;
    }

    private static String money(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f монет", value);
    }

    @Override
    public Optional<BalanceInfo> balance(UUID playerUuid) {
        if (!economyAvailable()) return Optional.empty();
        double value = balances.getOrDefault(playerUuid, 0.0);
        return Optional.of(new BalanceInfo(value, money(value), "монет"));
    }

    @Override
    public Optional<BalanceChange> deposit(UUID playerUuid, double amount) {
        if (!economyAvailable()) return Optional.empty();
        double before = balances.getOrDefault(playerUuid, 0.0);
        double after = before + amount;
        balances.put(playerUuid, after);
        return Optional.of(new BalanceChange(true, null, before, after, money(after)));
    }

    @Override
    public Optional<BalanceChange> withdraw(UUID playerUuid, double amount) {
        if (!economyAvailable()) return Optional.empty();
        double before = balances.getOrDefault(playerUuid, 0.0);
        if (amount > before) {
            // Ровно так ведёт себя настоящий провайдер: отказ с текстом, а не
            // уход баланса в минус.
            return Optional.of(new BalanceChange(false, "Недостаточно средств", before, before, money(before)));
        }
        double after = before - amount;
        balances.put(playerUuid, after);
        return Optional.of(new BalanceChange(true, null, before, after, money(after)));
    }

    @Override
    public Optional<EconomySummary> economySummary(int topLimit) {
        if (!economyAvailable()) return Optional.empty();
        double total = 0;
        List<EconomySummary.TopEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, Double> entry : balances.entrySet()) {
            total += entry.getValue();
            entries.add(new EconomySummary.TopEntry(
                    playerNames.getOrDefault(entry.getKey(), entry.getKey().toString()),
                    entry.getKey().toString(),
                    entry.getValue(),
                    money(entry.getValue())));
        }
        entries.sort(Comparator.comparingDouble(EconomySummary.TopEntry::balance).reversed());
        if (entries.size() > topLimit) entries = new ArrayList<>(entries.subList(0, topLimit));
        return Optional.of(new EconomySummary(total, money(total), "монет", balances.size(), List.copyOf(entries)));
    }
}
