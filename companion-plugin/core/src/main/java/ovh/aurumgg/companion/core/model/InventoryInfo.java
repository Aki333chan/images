package ovh.aurumgg.companion.core.model;

import java.util.List;

/**
 * Инвентарь игрока.
 *
 * @param items  основной инвентарь, слоты 0-35 (0-8 — хотбар)
 * @param armor  броня, слоты 0-3 (ботинки, поножи, нагрудник, шлем)
 * @param offhand предмет во второй руке или null
 */
public record InventoryInfo(List<ItemInfo> items, List<ItemInfo> armor, ItemInfo offhand) {}
