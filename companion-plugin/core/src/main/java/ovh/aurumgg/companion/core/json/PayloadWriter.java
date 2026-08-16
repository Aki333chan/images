package ovh.aurumgg.companion.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ovh.aurumgg.companion.core.model.InventoryInfo;
import ovh.aurumgg.companion.core.model.ItemInfo;
import ovh.aurumgg.companion.core.model.PermissionsInfo;
import ovh.aurumgg.companion.core.model.PlayerInfo;
import ovh.aurumgg.companion.core.model.PluginInfo;

/** Сериализация ответов плагина. Формат зафиксирован в docs/companion.md. */
public final class PayloadWriter {

    private PayloadWriter() {}

    public static String players(List<PlayerInfo> players) {
        List<String> items = new ArrayList<>(players.size());
        for (PlayerInfo p : players) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("uuid", Json.string(p.uuid().toString()));
            fields.put("name", Json.string(p.name()));
            fields.put("health", Json.number(p.health()));
            fields.put("maxHealth", Json.number(p.maxHealth()));
            fields.put("world", Json.string(p.world()));
            fields.put("x", Json.number(round(p.x())));
            fields.put("y", Json.number(round(p.y())));
            fields.put("z", Json.number(round(p.z())));
            fields.put("ping", Json.number(p.ping()));
            items.add(Json.object(fields));
        }
        return Json.object(Map.of("players", Json.array(items)));
    }

    public static String inventory(InventoryInfo inventory) {
        Map<String, String> root = new LinkedHashMap<>();
        root.put("items", itemArray(inventory.items()));
        root.put("armor", itemArray(inventory.armor()));
        root.put("offhand", inventory.offhand() == null ? "null" : item(inventory.offhand()));
        return Json.object(root);
    }

    public static String plugins(List<PluginInfo> plugins) {
        List<String> items = new ArrayList<>(plugins.size());
        for (PluginInfo p : plugins) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("name", Json.string(p.name()));
            fields.put("version", Json.string(p.version()));
            fields.put("enabled", p.enabled() ? "true" : "false");
            items.add(Json.object(fields));
        }
        return Json.object(Map.of("plugins", Json.array(items)));
    }

    public static String permissions(PermissionsInfo info) {
        Map<String, String> root = new LinkedHashMap<>();
        root.put("primaryGroup", Json.string(info.primaryGroup()));

        List<String> groups = new ArrayList<>(info.groups().size());
        for (String group : info.groups()) groups.add(Json.string(group));
        root.put("groups", Json.array(groups));

        List<String> nodes = new ArrayList<>(info.permissions().size());
        for (PermissionsInfo.PermissionEntry entry : info.permissions()) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("permission", Json.string(entry.permission()));
            fields.put("value", entry.value() ? "true" : "false");
            nodes.add(Json.object(fields));
        }
        root.put("permissions", Json.array(nodes));
        return Json.object(root);
    }

    /**
     * Ошибка с машиночитаемым кодом. Панель по коду решает, что показать:
     * «поставьте LuckPerms» — это не то же самое, что «игрок не найден»,
     * а разбирать русский текст на той стороне никуда не годится.
     */
    public static String error(String message, String code) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("error", Json.string(message));
        fields.put("code", Json.string(code));
        return Json.object(fields);
    }

    public static String error(String message) {
        return Json.object(Map.of("error", Json.string(message)));
    }

    public static String ok() {
        return Json.object(Map.of("ok", "true"));
    }

    private static String itemArray(List<ItemInfo> items) {
        List<String> parts = new ArrayList<>(items.size());
        for (ItemInfo item : items) parts.add(item(item));
        return Json.array(parts);
    }

    private static String item(ItemInfo item) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("slot", Json.number(item.slot()));
        fields.put("id", Json.string(item.id()));
        fields.put("count", Json.number(item.count()));
        fields.put("displayName", Json.string(item.displayName()));

        Map<String, String> enchants = new LinkedHashMap<>();
        if (item.enchantments() != null) {
            item.enchantments().forEach((key, level) -> enchants.put(key, Json.number(level)));
        }
        fields.put("enchantments", Json.object(enchants));

        List<String> lore = new ArrayList<>();
        if (item.lore() != null) {
            for (String line : item.lore()) lore.add(Json.string(line));
        }
        fields.put("lore", Json.array(lore));
        return Json.object(fields);
    }

    /** Координаты с точностью до сотых — большего в интерфейсе не нужно. */
    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
